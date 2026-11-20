package com.bifos.assistant.workspace.infra;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.workspace.domain.WorkspaceCursor;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspaceListing;
import com.bifos.assistant.workspace.domain.WorkspaceOpenedFile;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실행 공간의 파일 시스템을 읽는 유일한 곳이다. 규칙은 {@code backend/docs/code-architecture.md} 의 「경로 규칙」 이 갖는다.
 *
 * <p>링크를 따라가지 않는다. 주인 디렉터리부터 조각마다 {@link SecureDirectoryStream} 의 핸들에서 다음 조각을 열어
 * ({@code openat} + {@code O_NOFOLLOW}), 판정한 뒤 다른 것으로 바뀐 경로를 따라가지 않는다. 핸들로 따라오므로 확인과 열기
 * 사이에 경로가 바뀌어도 열려 있는 핸들이 가리키는 파일은 바뀌지 않는다. 링크 바꿔치기 경쟁(#359)이 여기서 막힌다.
 *
 * <p>{@link SecureDirectoryStream} 을 쓸 수 없는 운영체제(musl)에서는 파일 공간을 끈다(fail-closed). 그 경우 모든 경로가
 * {@link ErrorCode#WORKSPACE_UNAVAILABLE} 로 실패해 다른 사용자의 목록이나 본문이 새지 않는다. 운영 이미지는 glibc 다.
 *
 * <p>예외 메시지와 로그에 경로를 싣지 않는다. 부르는 쪽도 {@link IOException} 의 메시지를 남기지 않는다.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WorkspaceTree {

    private static final long LIST_BUDGET_NANOS = 2_000_000_000L;
    private static final Comparator<WorkspaceEntry> DIRECTORIES_FIRST = Comparator.comparing(
        (WorkspaceEntry entry) -> entry.kind() != WorkspaceEntryKind.DIRECTORY)
        .thenComparing(WorkspaceEntry::name);


    /** 링크가 아닌 디렉터리면 참이다. */
    public static boolean isDirectoryNoFollow(Path dir) {
        return Files.isDirectory(dir, NOFOLLOW_LINKS);
    }


    /**
     * 디렉터리 전체에서 다음 {@code limit + 1} 개만 유지하고 {@code limit} 개를 디렉터리 먼저 이름 순서로 준다.
     *
     * <p>주인 디렉터리가 없으면 빈 경로에만 빈 목록을 준다. 주인 디렉터리가 링크이거나, 조각이 링크이거나 디렉터리가 아니거나 없으면
     * 비어 있다.
     */
    public static Optional<WorkspaceListing> list(Path ownerDir, WorkspacePath path, int limit) throws IOException {
        return list(ownerDir, path, limit, null, System::nanoTime);
    }


    public static Optional<WorkspaceListing> list(Path ownerDir, WorkspacePath path, int limit, String rawCursor)
        throws IOException {
        return list(ownerDir, path, limit, rawCursor, System::nanoTime);
    }


    /** 시험이 시간을 제어해 실제 대기 없이 예산 초과를 확인한다. 운영에서는 nanoTime만 쓴다. */
    static Optional<WorkspaceListing> list(
        Path ownerDir, WorkspacePath path, int limit, String rawCursor, LongSupplier nanoTime) throws IOException {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("workspace page size is invalid");
        }
        WorkspaceCursor cursor = WorkspaceCursor.decode(rawCursor, path);
        long started = nanoTime.getAsLong();
        if (Files.notExists(ownerDir, NOFOLLOW_LINKS)) {
            return path.isRoot() ? Optional.of(new WorkspaceListing("", List.of(), false)) : Optional.empty();
        }
        try {
            Optional<DirectoryStream<Path>> opened = openDirectory(ownerDir, path.segments());
            if (opened.isEmpty()) {
                return Optional.empty();
            }
            PriorityQueue<WorkspaceEntry> next = new PriorityQueue<>(limit + 1, DIRECTORIES_FIRST.reversed());
            try (DirectoryStream<Path> dir = opened.get()) {
                for (Path entry : dir) {
                    checkListDeadline(started, nanoTime);
                    try {
                        WorkspaceEntry candidate = entryOf(dir, entry);
                        if (cursor == null || cursor.precedes(candidate)) {
                            retainNext(next, candidate, limit);
                        }
                    } catch (NoSuchFileException ignored) {
                        // 읽는 사이에 지워진 항목은 목록에 넣지 않는다.
                    }
                }
            } catch (DirectoryIteratorException ex) {
                throw ex.getCause();
            }
            checkListDeadline(started, nanoTime);
            List<WorkspaceEntry> entries = new ArrayList<>(next);
            boolean truncated = entries.size() > limit;
            entries.sort(DIRECTORIES_FIRST);
            List<WorkspaceEntry> shown = List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
            String nextCursor = truncated ? WorkspaceCursor.encode(path, shown.get(shown.size() - 1)) : null;
            return Optional.of(new WorkspaceListing(path.value(), shown, truncated, nextCursor));
        } catch (NoSuchFileException | NotDirectoryException ex) {
            return Optional.empty();
        } catch (AccessDeniedException ex) {
            throw unreadable();
        }
    }


    /** 최대 힙의 용량은 lookahead를 포함한 limit + 1을 한 순간도 넘지 않는다. */
    static void retainNext(PriorityQueue<WorkspaceEntry> next, WorkspaceEntry candidate, int limit) {
        if (next.size() < limit + 1) {
            next.add(candidate);
        } else if (DIRECTORIES_FIRST.compare(candidate, next.element()) < 0) {
            next.remove();
            next.add(candidate);
        }
    }


    private static void checkListDeadline(long started, LongSupplier nanoTime) throws IOException {
        if (nanoTime.getAsLong() - started >= LIST_BUDGET_NANOS) {
            throw new IOException("workspace listing deadline exceeded");
        }
    }


    /** 마지막 조각을 링크를 따라가지 않고 본 목록 한 줄이다. 없거나 중간에 링크를 지나면 비어 있다. */
    public static Optional<WorkspaceEntry> stat(Path ownerDir, WorkspacePath path) throws IOException {
        if (path.isRoot()) {
            return Optional.empty();
        }
        try {
            Optional<DirectoryStream<Path>> opened = openDirectory(ownerDir, parentOf(path));
            if (opened.isEmpty()) {
                return Optional.empty();
            }
            try (DirectoryStream<Path> dir = opened.get()) {
                return Optional.of(entryOf(dir, ownerDir.resolve(path.value())));
            }
        } catch (NoSuchFileException | NotDirectoryException ex) {
            return Optional.empty();
        } catch (AccessDeniedException ex) {
            throw unreadable();
        }
    }


    /**
     * 일반 파일 하나를 링크를 따라가지 않고 연다. 열기 직전에 종류를 다시 본다. FIFO 를 열면 스레드가 막히므로 일반 파일이 아니면 열지
     * 않는다.
     *
     * @throws ApiException 없거나 링크를 지나거나 일반 파일이 아니면 {@code WORKSPACE_ENTRY_NOT_FOUND}, 하드 링크가 둘 이상이거나 권한이
     * 없으면 {@code WORKSPACE_ENTRY_UNREADABLE}
     */
    public static WorkspaceOpenedFile open(Path ownerDir, WorkspacePath path) throws IOException {
        if (path.isRoot()) {
            throw notFound();
        }
        try {
            Optional<DirectoryStream<Path>> opened = openDirectory(ownerDir, parentOf(path));
            if (opened.isEmpty()) {
                throw notFound();
            }
            try (DirectoryStream<Path> dir = opened.get()) {
                SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>)dir;
                Path entry = ownerDir.resolve(path.value());
                BasicFileAttributes attributes = attributesOf(dir, entry);
                if (!attributes.isRegularFile()) {
                    throw notFound();
                }
                if (linkCount(entry) > 1) {
                    throw unreadable();
                }
                SeekableByteChannel channel = secure.newByteChannel(entry.getFileName(), Set.of(READ, NOFOLLOW_LINKS));
                try {
                    long size = channel.size();
                    return new WorkspaceOpenedFile(
                        size,
                        attributes.lastModifiedTime().toInstant(),
                        limited(Channels.newInputStream(channel), size));
                } catch (IOException | RuntimeException ex) {
                    channel.close();
                    throw ex;
                }
            }
        } catch (NoSuchFileException | NotDirectoryException ex) {
            throw notFound();
        } catch (AccessDeniedException ex) {
            throw unreadable();
        }
    }


    /**
     * 주인 디렉터리부터 조각마다 {@link SecureDirectoryStream} 의 핸들에서 링크가 아닌 디렉터리인지 보고 열어 마지막 디렉터리의
     * 스트림을 준다. 하나라도 아니면 비어 있다. 스트림은 부르는 쪽이 닫는다.
     *
     * <p>{@link SecureDirectoryStream} 을 쓸 수 없으면 파일 공간을 끈다(fail-closed, {@link ErrorCode#WORKSPACE_UNAVAILABLE}).
     */
    private static Optional<DirectoryStream<Path>> openDirectory(Path ownerDir, List<String> segments)
        throws IOException {
        if (!isDirectoryNoFollow(ownerDir)) {
            return Optional.empty();
        }
        DirectoryStream<Path> first = Files.newDirectoryStream(ownerDir);
        if (!(first instanceof SecureDirectoryStream<Path> secure)) {
            first.close();
            throw secureDirectoryUnavailable();
        }
        SecureDirectoryStream<Path> dir = secure;
        for (String segment : segments) {
            SecureDirectoryStream<Path> next;
            try {
                next = openChild(dir, ownerDir.getFileSystem().getPath(segment));
            } finally {
                dir.close();
            }
            if (next == null) {
                return Optional.empty();
            }
            dir = next;
        }
        return Optional.of(dir);
    }


    /** 핸들 안의 이름이 링크가 아닌 디렉터리면 링크를 따라가지 않고 열고, 아니면 {@code null} 이다. */
    private static SecureDirectoryStream<Path> openChild(SecureDirectoryStream<Path> dir, Path name)
        throws IOException {
        try {
            BasicFileAttributes attributes = dir.getFileAttributeView(
                name, BasicFileAttributeView.class, NOFOLLOW_LINKS)
                .readAttributes();
            return attributes.isDirectory() ? dir.newDirectoryStream(name, NOFOLLOW_LINKS) : null;
        } catch (NoSuchFileException | NotDirectoryException ex) {
            return null;
        }
    }


    private static WorkspaceEntry entryOf(DirectoryStream<Path> dir, Path entry) throws IOException {
        BasicFileAttributes attributes = attributesOf(dir, entry);
        WorkspaceEntryKind kind = kindOf(attributes);
        boolean readable = switch (kind) {
            case FILE -> Files.isReadable(entry) && linkCount(entry) == 1;
            case DIRECTORY -> Files.isReadable(entry);
            case LINK, OTHER -> false;
        };
        String name = entry.getFileName().toString();
        return new WorkspaceEntry(
            name,
            kind,
            kind == WorkspaceEntryKind.FILE ? attributes.size() : null,
            attributes.lastModifiedTime().toInstant(),
            readable,
            WorkspacePath.addressable(name));
    }


    /** 링크를 따라가지 않은 속성이다. 핸들 안의 이름으로 본다. */
    private static BasicFileAttributes attributesOf(DirectoryStream<Path> dir, Path entry) throws IOException {
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>)dir;
        return secure.getFileAttributeView(entry.getFileName(), BasicFileAttributeView.class, NOFOLLOW_LINKS)
            .readAttributes();
    }


    private static WorkspaceEntryKind kindOf(BasicFileAttributes attributes) {
        if (attributes.isSymbolicLink()) {
            return WorkspaceEntryKind.LINK;
        }
        if (attributes.isDirectory()) {
            return WorkspaceEntryKind.DIRECTORY;
        }
        return attributes.isRegularFile() ? WorkspaceEntryKind.FILE : WorkspaceEntryKind.OTHER;
    }


    /** 연 시점의 크기까지만 읽는다. 그 사이 파일이 커져도 본문이 {@code Content-Length} 를 넘지 않는다. */
    private static InputStream limited(InputStream in, long limit) {
        return new FilterInputStream(in) {
            private long left = limit;


            @Override
            public int read() throws IOException {
                int value = left > 0 ? super.read() : -1;
                if (value >= 0) {
                    left--;
                }
                return value;
            }


            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (left <= 0) {
                    return length == 0 ? 0 : -1;
                }
                int read = super.read(buffer, offset, (int)Math.min(length, left));
                if (read > 0) {
                    left -= read;
                }
                return read;
            }


            @Override
            public long skip(long count) throws IOException {
                long skipped = super.skip(Math.min(count, left));
                left -= skipped;
                return skipped;
            }


            @Override
            public int available() throws IOException {
                return (int)Math.min(super.available(), left);
            }
        };
    }


    /** 하드 링크 수다. 그 속성을 읽지 못하는 파일 시스템이면 1 로 본다. */
    private static int linkCount(Path entry) throws IOException {
        try {
            return (Integer)Files.getAttribute(entry, "unix:nlink", NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException ex) {
            return 1;
        }
    }


    private static List<String> parentOf(WorkspacePath path) {
        return path.segments().subList(0, path.segments().size() - 1);
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND, "workspace entry not found");
    }


    private static ApiException unreadable() {
        return new ApiException(ErrorCode.WORKSPACE_ENTRY_UNREADABLE, "workspace entry is not readable");
    }


    private static ApiException secureDirectoryUnavailable() {
        return new ApiException(ErrorCode.WORKSPACE_UNAVAILABLE, "secure workspace access is unavailable");
    }
}
