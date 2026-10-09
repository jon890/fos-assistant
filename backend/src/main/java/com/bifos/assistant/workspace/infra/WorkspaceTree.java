package com.bifos.assistant.workspace.infra;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실행 공간의 파일 시스템을 읽는 유일한 곳이다. 규칙은 {@code docs/code-architecture.md} 의 「경로 규칙」 이 갖는다.
 *
 * <p>링크를 따라가지 않는다. 주인 디렉터리부터 조각마다 링크가 아닌 디렉터리인지 보고 연다. {@link SecureDirectoryStream} 이
 * 있으면 앞 디렉터리의 핸들에서 다음 조각을 열어, 판정한 뒤 다른 것으로 바뀐 경로를 따라가지 않는다. 없는 운영체제는 조각마다 경로로
 * 다시 보고 경고를 한 번 남긴다.
 *
 * <p>대체 경로는 읽은 뒤에 다시 본다. 중간 조각이 여전히 링크가 아닌 디렉터리이고 실제 경로가 주인 디렉터리 아래인지, 연 파일의
 * {@code fileKey} 가 열기 전과 같은지다. 확인과 열기 사이의 틈은 이 사후 확인으로 줄일 뿐 없애지 못한다. 운영 이미지가 musl 이라
 * 이 경로를 탄다. glibc 이미지와 {@link SecureDirectoryStream} 만 쓰는 쪽(fail-closed)으로 옮기는 일은 #359 가 갖는다.
 *
 * <p>예외 메시지와 로그에 경로를 싣지 않는다. 부르는 쪽도 {@link IOException} 의 메시지를 남기지 않는다.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WorkspaceTree {

    private static final AtomicBoolean FALLBACK_WARNING_LOGGED = new AtomicBoolean();
    private static final Comparator<WorkspaceEntry> DIRECTORIES_FIRST = Comparator.comparing(
                    (WorkspaceEntry entry) -> entry.kind() != WorkspaceEntryKind.DIRECTORY)
            .thenComparing(WorkspaceEntry::name);

    /** 링크가 아닌 디렉터리면 참이다. */
    public static boolean isDirectoryNoFollow(Path dir) {
        return Files.isDirectory(dir, NOFOLLOW_LINKS);
    }

    /**
     * 디렉터리 하나의 목록이다. 항목은 {@code limit + 1} 개까지만 읽고, 그 가운데 {@code limit} 개를 디렉터리 먼저 이름 순서로 준다.
     *
     * <p>주인 디렉터리가 없으면 빈 경로에만 빈 목록을 준다. 주인 디렉터리가 링크이거나, 조각이 링크이거나 디렉터리가 아니거나 없으면
     * 비어 있다.
     */
    public static Optional<WorkspaceListing> list(Path ownerDir, WorkspacePath path, int limit) throws IOException {
        return list(ownerDir, path, limit, false, () -> {});
    }

    /**
     * 공개 메서드가 부르는 본체다. {@code checkedPath} 면 {@link SecureDirectoryStream} 이 있어도 대체 경로를 탄다.
     * {@code beforeRecheck} 는 대체 경로에서 읽기와 사후 확인 사이에 부른다. 둘 다 시험이 대체 경로를 직접 부르려고 둔다.
     */
    static Optional<WorkspaceListing> list(
            Path ownerDir, WorkspacePath path, int limit, boolean checkedPath, Runnable beforeRecheck)
            throws IOException {
        if (Files.notExists(ownerDir, NOFOLLOW_LINKS)) {
            return path.isRoot() ? Optional.of(new WorkspaceListing("", List.of(), false)) : Optional.empty();
        }
        try {
            Optional<DirectoryStream<Path>> opened = openDirectory(ownerDir, path.segments(), checkedPath);
            if (opened.isEmpty()) {
                return Optional.empty();
            }
            boolean checked = checked(checkedPath, opened.get());
            List<WorkspaceEntry> entries = new ArrayList<>();
            try (DirectoryStream<Path> dir = opened.get()) {
                for (Path entry : dir) {
                    if (entries.size() > limit) {
                        break;
                    }
                    try {
                        entries.add(entryOf(dir, entry));
                    } catch (NoSuchFileException ignored) {
                        // 읽는 사이에 지워진 항목은 목록에 넣지 않는다.
                    }
                }
            } catch (DirectoryIteratorException ex) {
                throw ex.getCause();
            }
            if (checked && !stillInside(ownerDir, path.segments(), beforeRecheck)) {
                return Optional.empty();
            }
            boolean truncated = entries.size() > limit;
            entries.sort(DIRECTORIES_FIRST);
            List<WorkspaceEntry> shown = List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
            return Optional.of(new WorkspaceListing(path.value(), shown, truncated));
        } catch (AccessDeniedException ex) {
            throw unreadable();
        }
    }

    /** 마지막 조각을 링크를 따라가지 않고 본 목록 한 줄이다. 없거나 중간에 링크를 지나면 비어 있다. */
    public static Optional<WorkspaceEntry> stat(Path ownerDir, WorkspacePath path) throws IOException {
        return stat(ownerDir, path, false, () -> {});
    }

    static Optional<WorkspaceEntry> stat(Path ownerDir, WorkspacePath path, boolean checkedPath, Runnable beforeRecheck)
            throws IOException {
        if (path.isRoot()) {
            return Optional.empty();
        }
        try {
            Optional<DirectoryStream<Path>> opened = openDirectory(ownerDir, parentOf(path), checkedPath);
            if (opened.isEmpty()) {
                return Optional.empty();
            }
            try (DirectoryStream<Path> dir = opened.get()) {
                WorkspaceEntry entry = entryOf(dir, ownerDir.resolve(path.value()));
                boolean inside = !checked(checkedPath, dir) || stillInside(ownerDir, parentOf(path), beforeRecheck);
                return inside ? Optional.of(entry) : Optional.empty();
            }
        } catch (NoSuchFileException ex) {
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
     *     없으면 {@code WORKSPACE_ENTRY_UNREADABLE}
     */
    public static WorkspaceOpenedFile open(Path ownerDir, WorkspacePath path) throws IOException {
        return open(ownerDir, path, false, () -> {});
    }

    static WorkspaceOpenedFile open(Path ownerDir, WorkspacePath path, boolean checkedPath, Runnable beforeRecheck)
            throws IOException {
        if (path.isRoot()) {
            throw notFound();
        }
        try {
            Optional<DirectoryStream<Path>> opened = openDirectory(ownerDir, parentOf(path), checkedPath);
            if (opened.isEmpty()) {
                throw notFound();
            }
            try (DirectoryStream<Path> dir = opened.get()) {
                Path entry = ownerDir.resolve(path.value());
                BasicFileAttributes attributes = attributesOf(dir, entry);
                if (!attributes.isRegularFile()) {
                    throw notFound();
                }
                if (linkCount(entry) > 1) {
                    throw unreadable();
                }
                SeekableByteChannel channel = dir instanceof SecureDirectoryStream<Path> secure
                        ? secure.newByteChannel(entry.getFileName(), Set.of(READ, NOFOLLOW_LINKS))
                        : Files.newByteChannel(entry, READ, NOFOLLOW_LINKS);
                try {
                    if (checked(checkedPath, dir)
                            && !(stillInside(ownerDir, parentOf(path), beforeRecheck) && sameFile(entry, attributes))) {
                        throw notFound();
                    }
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
        } catch (NoSuchFileException ex) {
            throw notFound();
        } catch (AccessDeniedException ex) {
            throw unreadable();
        }
    }

    /**
     * 주인 디렉터리부터 조각마다 링크가 아닌 디렉터리인지 보고 열어 마지막 디렉터리의 스트림을 준다. 하나라도 아니면 비어 있다.
     * 스트림은 부르는 쪽이 닫는다.
     */
    private static Optional<DirectoryStream<Path>> openDirectory(
            Path ownerDir, List<String> segments, boolean checkedPath) throws IOException {
        if (!isDirectoryNoFollow(ownerDir)) {
            return Optional.empty();
        }
        DirectoryStream<Path> first = Files.newDirectoryStream(ownerDir);
        if (!(first instanceof SecureDirectoryStream<Path> secure) || checkedPath) {
            // 운영 이미지(musl)가 타는 대체 경로다. 틈은 사후 확인(stillInside, sameFile)으로 줄일 뿐 없애지 못한다. #359
            first.close();
            warnFallbackOnce();
            Path dir = ownerDir;
            for (String segment : segments) {
                dir = dir.resolve(segment);
                if (!isDirectoryNoFollow(dir)) {
                    return Optional.empty();
                }
            }
            return Optional.of(Files.newDirectoryStream(dir));
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

    /** 링크를 따라가지 않은 속성이다. 핸들이 있으면 그 핸들 안의 이름으로 본다. */
    private static BasicFileAttributes attributesOf(DirectoryStream<Path> dir, Path entry) throws IOException {
        if (dir instanceof SecureDirectoryStream<Path> secure) {
            return secure.getFileAttributeView(entry.getFileName(), BasicFileAttributeView.class, NOFOLLOW_LINKS)
                    .readAttributes();
        }
        return Files.readAttributes(entry, BasicFileAttributes.class, NOFOLLOW_LINKS);
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

    /** 핸들로 조각을 따라오지 않아 사후 확인이 필요한지다. 대체 경로가 연 마지막 스트림은 핸들이어도 경로로 연 것이다. */
    private static boolean checked(boolean checkedPath, DirectoryStream<Path> dir) {
        return checkedPath || !(dir instanceof SecureDirectoryStream);
    }

    /**
     * 대체 경로에서 읽은 뒤 다시 본다. 주인 디렉터리부터 조각이 모두 링크가 아닌 디렉터리이고, 마지막 디렉터리의 실제 경로가 주인
     * 디렉터리의 실제 경로 아래여야 참이다.
     */
    private static boolean stillInside(Path ownerDir, List<String> segments, Runnable beforeRecheck)
            throws IOException {
        beforeRecheck.run();
        Path dir = ownerDir;
        if (!isDirectoryNoFollow(dir)) {
            return false;
        }
        for (String segment : segments) {
            dir = dir.resolve(segment);
            if (!isDirectoryNoFollow(dir)) {
                return false;
            }
        }
        try {
            return dir.toRealPath().startsWith(ownerDir.toRealPath());
        } catch (NoSuchFileException ex) {
            return false;
        }
    }

    /** 마지막 조각을 링크 없이 다시 본 파일이 열기 전과 같은지다. {@code fileKey} 가 없으면 크기와 수정 시각으로 본다. */
    private static boolean sameFile(Path entry, BasicFileAttributes before) throws IOException {
        try {
            BasicFileAttributes after = Files.readAttributes(entry, BasicFileAttributes.class, NOFOLLOW_LINKS);
            return before.fileKey() != null
                    ? before.fileKey().equals(after.fileKey())
                    : before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime());
        } catch (NoSuchFileException ex) {
            return false;
        }
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
                int read = super.read(buffer, offset, (int) Math.min(length, left));
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
                return (int) Math.min(super.available(), left);
            }
        };
    }

    /** 하드 링크 수다. 그 속성을 읽지 못하는 파일 시스템이면 1 로 본다. */
    private static int linkCount(Path entry) throws IOException {
        try {
            return (Integer) Files.getAttribute(entry, "unix:nlink", NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException ex) {
            return 1;
        }
    }

    private static List<String> parentOf(WorkspacePath path) {
        return path.segments().subList(0, path.segments().size() - 1);
    }

    private static void warnFallbackOnce() {
        if (FALLBACK_WARNING_LOGGED.compareAndSet(false, true)) {
            log.warn("SecureDirectoryStream is unavailable; checking each workspace path segment for links instead");
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND, "workspace entry not found");
    }

    private static ApiException unreadable() {
        return new ApiException(ErrorCode.WORKSPACE_ENTRY_UNREADABLE, "workspace entry is not readable");
    }
}
