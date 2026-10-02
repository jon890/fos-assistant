package com.bifos.assistant.chat.infra;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 대화마다 둔 결과물 폴더를 만들고 훑고 파일을 찾고 지운다.
 *
 * <p>경로를 만드는 규칙이 이 클래스 하나에만 있다. 폴더는 {@code {root}/{대화 번호}} 이고 그 안은 에이전트가
 * 정한다. 대화 폴더 밖인지 판정하는 것도 여기서 한다. 규칙이 흩어지면 판정하는 쪽이나 지우는 쪽이 놓친다.
 * 근거는 ADR-027 에 있다.
 */
@Component
@Slf4j
public class ArtifactStore {

    static final String HTML = "html";
    private static final AtomicBoolean UNSUPPORTED_SECURE_DIRECTORY_WARNING_LOGGED = new AtomicBoolean();

    /**
     * 내주는 확장자와 그 형식. 이 밖의 파일은 폴더에 있어도 내주지 않는다.
     *
     * <p>SVG 는 스크립트를 품을 수 있어 받지 않는다.
     */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            HTML,
            "text/html; charset=utf-8",
            "css",
            "text/css; charset=utf-8",
            "png",
            "image/png",
            "jpg",
            "image/jpeg",
            "jpeg",
            "image/jpeg",
            "gif",
            "image/gif",
            "webp",
            "image/webp");

    private final Path root;
    private final String agentRoot;
    private final boolean forceAtomicMoveFallback;
    private final ArtifactAtomicMover atomicMover;

    @Autowired
    public ArtifactStore(ArtifactProperties properties) {
        this(properties, false, ArtifactStore::atomicMove);
    }

    /** Linux에서도 링크 재검사와 원자 교체 대체 경로를 검사할 때 쓴다. */
    ArtifactStore(ArtifactProperties properties, boolean forceAtomicMoveFallback) {
        this(properties, forceAtomicMoveFallback, ArtifactStore::atomicMove);
    }

    /** 파일 시스템의 원자 교체 실패를 결정적으로 검사할 때 쓴다. */
    ArtifactStore(ArtifactProperties properties, boolean forceAtomicMoveFallback, ArtifactAtomicMover atomicMover) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.agentRoot = stripTrailingSlash(properties.agentRoot());
        this.forceAtomicMoveFallback = forceAtomicMoveFallback;
        this.atomicMover = atomicMover;
    }

    /** 확장자로 정한 형식. 내주지 않는 확장자면 빈 값이다. 대소문자는 가리지 않는다. */
    public static Optional<String> contentTypeOf(String relativePath) {
        return Optional.ofNullable(CONTENT_TYPES.get(extensionOf(relativePath)));
    }

    /**
     * 대화 폴더를 만든다. 이미 있으면 그대로 둔다.
     *
     * <p>만들지 못해도 turn 을 멈추지 않는다. 에이전트가 파일을 쓰지 못할 뿐 대화는 이어진다. 경고 로그로 남긴다.
     */
    public Path ensureFolder(Long conversationId) {
        Path folder = folderOf(conversationId);
        try {
            Files.createDirectories(folder);
        } catch (IOException ex) {
            log.warn("could not create the artifact folder conversationId={}", conversationId, ex);
        }
        return folder;
    }

    /** 같은 폴더를 Hermes 컨테이너에서 보는 경로. 끝에 {@code /} 를 붙이지 않는다. */
    public String agentFolder(Long conversationId) {
        return agentRoot + "/" + conversationId;
    }

    /**
     * 마지막으로 바뀐 때가 {@code since} 이후인 {@code .html} 파일을 하위 폴더까지 찾는다.
     *
     * <p>심볼릭 링크는 따라가지 않는다. 폴더 밖을 가리키는 링크를 답에 묶지 않으려는 것이다. 폴더가 없으면 빈
     * 목록이다.
     */
    public List<ArtifactFoundFile> changedHtmlSince(Long conversationId, Instant since) {
        Path folder = folderOf(conversationId);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<ArtifactFoundFile> found = new ArrayList<>();
        for (WalkedFile file : regularFilesUnder(folder)) {
            if (HTML.equals(extensionOf(file.path().getFileName().toString()))
                    && !file.modified().isBefore(since)) {
                found.add(new ArtifactFoundFile(relativeOf(folder, file.path()), file.byteSize()));
            }
        }
        return found;
    }

    /**
     * 대화 폴더 안의 파일을 찾는다. 없거나, 내주지 않는 확장자이거나, 폴더 밖이면 빈 값이다.
     *
     * <p>판정은 파일을 열기 전에 끝낸다. 요청 경로의 {@code ..} 과 심볼릭 링크를 모두 풀어야 폴더 밖인지 알 수
     * 있으므로 폴더와 파일을 {@code toRealPath()} 로 푼 뒤 견준다. 풀린 대상의 확장자도 다시 본다. 허용한 이름의
     * 링크가 폴더 안의 SVG 를 가리키게 만들 수 있기 때문이다.
     */
    public Optional<Path> resolveInside(Long conversationId, String relativePath) {
        if (relativePath == null
                || relativePath.isBlank()
                || contentTypeOf(relativePath).isEmpty()) {
            return Optional.empty();
        }
        try {
            Path requested = Path.of(relativePath);
            if (requested.isAbsolute()) {
                return Optional.empty();
            }
            Path folder = folderOf(conversationId);
            // 대화 폴더 자체가 링크면 판정 기준까지 링크를 따라가 다른 대화나 폴더 밖을 내준다. 링크를 따라가지 않고 본다.
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path realFolder = root.toRealPath().resolve(String.valueOf(conversationId));
            Path realFile = folder.resolve(requested).toRealPath();
            if (!realFile.startsWith(realFolder)
                    || !Files.isRegularFile(realFile)
                    || contentTypeOf(realFile.getFileName().toString()).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(realFile);
        } catch (NoSuchFileException ex) {
            return Optional.empty();
        } catch (IOException | RuntimeException ex) {
            // 경로로 쓸 수 없는 글자가 섞인 요청도 여기로 온다. 없는 파일과 같게 다룬다.
            log.debug("could not resolve an artifact path conversationId={}", conversationId, ex);
            return Optional.empty();
        }
    }

    /**
     * 결과물을 쓸 최종 경로를 판정하고 필요한 부모 폴더를 만든다.
     *
     * <p>기존 읽기 경로와 달리 아직 없는 최종 파일도 받는다. 대화 폴더와 이미 있던 부모는 링크를 따라가지
     * 않고 확인하고, 새 부모는 하나씩 만든 직후 실제 경로를 다시 확인한다.
     */
    public Path resolveForWrite(Long conversationId, String relativePath) {
        try {
            List<String> parts = writableParts(relativePath);
            Path folder = checkedConversationFolder(conversationId);
            Path current = folder;
            for (int index = 0; index < parts.size() - 1; index++) {
                current = checkedOrCreatedDirectory(current, parts.get(index), folder);
            }
            Path target = current.resolve(parts.getLast());
            requireOrdinaryTarget(target);
            return target;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw storeFailure(ex);
        }
    }

    /** 파일 시스템을 열지 않고 결과물 쓰기 경로의 형식과 확장자만 검사한다. */
    public static void requireWritablePath(String relativePath) {
        writableParts(relativePath);
    }

    /**
     * 검증한 대화 폴더 안에서 임시 파일을 완성한 뒤 원자적으로 바꾼다.
     *
     * <p>원자 교체를 지원하지 않는 파일 시스템에서는 기존 파일을 직접 덮지 않는다. 실패한 저장이 기존
     * 결과물을 망가뜨리지 않게 하기 위해서다.
     */
    public long write(Long conversationId, String relativePath, byte[] content) {
        if (content == null) {
            throw validation("artifact content is required");
        }
        Path target = resolveForWrite(conversationId, relativePath);
        Path parent = target.getParent();
        try {
            Path folder = checkedConversationFolder(conversationId);
            verifyParent(parent, folder);
            requireOrdinaryTarget(target);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                if (!forceAtomicMoveFallback && stream instanceof SecureDirectoryStream<Path> secure) {
                    writeWithSecureDirectory(secure, target.getFileName(), content);
                } else {
                    if (!forceAtomicMoveFallback) {
                        warnUnsupportedSecureDirectoryOnce();
                    } else {
                        log.warn("using forced checked atomic artifact replacement for verification");
                    }
                    writeWithAtomicMove(parent, target, folder, content, atomicMover);
                }
            }
            return content.length;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw storeFailure(ex);
        }
    }

    /**
     * 루트 아래를 걸어 마지막으로 바뀐 때가 {@code cutoff} 보다 앞선 파일을 지운다.
     *
     * <p>대화 번호 이름의 폴더 안에 있는 파일만 지운다. 한 파일이 실패해도 나머지를 계속한다. 하나 때문에 그날
     * 치가 통째로 멈추면 디스크가 계속 찬다. 빈 폴더는 남긴다.
     *
     * @return 지운 파일들의 대화 번호와 상대 경로
     */
    public List<ArtifactRemoved> deleteOlderThan(Instant cutoff) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        // 보관 기간은 대화 폴더 단위로 센다. 파일마다 세면 다음 turn 이 HTML 만 고쳤을 때 그 HTML 이 부르는
        // 옛 사진이 먼저 지워져 사진이 깨진 초안이 남는다. 폴더에서 가장 늦게 바뀐 파일이 기간을 넘기면 함께 지운다.
        Map<Long, List<Path>> filesByConversation = new HashMap<>();
        Map<Long, Instant> latestByConversation = new HashMap<>();
        for (WalkedFile walked : regularFilesUnder(root)) {
            Path relative = root.relativize(walked.path());
            if (relative.getNameCount() < 2) {
                continue;
            }
            Long conversationId = conversationIdOf(relative.getName(0).toString());
            if (conversationId == null) {
                continue;
            }
            filesByConversation
                    .computeIfAbsent(conversationId, id -> new ArrayList<>())
                    .add(walked.path());
            latestByConversation.merge(
                    conversationId, walked.modified(), (left, right) -> left.isAfter(right) ? left : right);
        }
        List<ArtifactRemoved> removed = new ArrayList<>();
        for (Map.Entry<Long, List<Path>> entry : filesByConversation.entrySet()) {
            Long conversationId = entry.getKey();
            if (!latestByConversation.get(conversationId).isBefore(cutoff)) {
                continue;
            }
            Path folder = folderOf(conversationId);
            for (Path file : entry.getValue()) {
                try {
                    Files.deleteIfExists(file);
                    removed.add(new ArtifactRemoved(conversationId, relativeOf(folder, file)));
                } catch (IOException | RuntimeException ex) {
                    log.warn("could not delete an expired artifact file conversationId={}", conversationId, ex);
                }
            }
        }
        return removed;
    }

    /** 걸음에서 만난 일반 파일 하나와 그때 읽은 속성이다. */
    private record WalkedFile(Path path, Instant modified, long byteSize) {}

    /**
     * {@code start} 아래의 일반 파일을 하위 폴더까지 모은다. 심볼릭 링크는 따라가지 않는다.
     *
     * <p>읽지 못한 폴더나 파일은 경고 로그만 남기고 건너뛴다. 하나 때문에 나머지를 놓치지 않으려는 것이다.
     */
    private static List<WalkedFile> regularFilesUnder(Path start) {
        List<WalkedFile> files = new ArrayList<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        files.add(new WalkedFile(
                                file, attributes.lastModifiedTime().toInstant(), attributes.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException ex) {
                    log.warn("could not read an artifact path while walking, skipped", ex);
                    return FileVisitResult.CONTINUE;
                }

                /** 폴더 목록을 읽는 도중 실패해도 그 폴더만 두고 걸음을 이어 간다. */
                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException ex) {
                    if (ex != null) {
                        log.warn("could not read an artifact path while walking, skipped", ex);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            // 방문기가 실패를 삼키므로 여기로 오는 것은 걸음을 시작하지도 못한 경우다.
            log.warn("could not walk the artifact folder", ex);
        }
        return files;
    }

    private Path folderOf(Long conversationId) {
        return root.resolve(String.valueOf(conversationId));
    }

    private Path checkedConversationFolder(Long conversationId) throws IOException {
        if (conversationId == null) {
            throw validation("conversation id is required");
        }
        Path folder = folderOf(conversationId);
        // 대화 준비에서는 생성 실패를 기록하고 계속하지만, 명시적인 쓰기는 실패를 호출자에게 돌린다.
        Files.createDirectories(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) {
            throw storeFailure(new IOException("artifact conversation folder is not a directory"));
        }
        Path realRoot = root.toRealPath();
        Path realFolder = folder.toRealPath();
        if (!realFolder.equals(realRoot.resolve(String.valueOf(conversationId)))) {
            throw storeFailure(new IOException("artifact conversation folder escapes the root"));
        }
        return realFolder;
    }

    private static List<String> writableParts(String relativePath) {
        if (relativePath == null
                || relativePath.isBlank()
                || relativePath.length() > 500
                || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf('\0') >= 0
                || relativePath.matches("^[A-Za-z]:.*")) {
            throw validation("artifact path is invalid");
        }
        Path parsed;
        try {
            parsed = Path.of(relativePath);
        } catch (RuntimeException ex) {
            throw validation("artifact path is invalid");
        }
        if (parsed.isAbsolute() || contentTypeOf(relativePath).isEmpty()) {
            throw validation("artifact path is invalid");
        }
        String[] rawParts = relativePath.split("/", -1);
        List<String> parts = new ArrayList<>(rawParts.length);
        for (String part : rawParts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw validation("artifact path is invalid");
            }
            parts.add(part);
        }
        return parts;
    }

    private static Path checkedOrCreatedDirectory(Path parent, String name, Path folder) throws IOException {
        Path child = parent.resolve(name);
        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) {
                throw storeFailure(new IOException("artifact parent is not a directory"));
            }
        } else {
            Files.createDirectory(child);
        }
        Path realChild = child.toRealPath();
        if (!realChild.startsWith(folder)) {
            throw storeFailure(new IOException("artifact parent escapes the conversation folder"));
        }
        return realChild;
    }

    private static void verifyParent(Path parent, Path folder) throws IOException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw storeFailure(new IOException("artifact parent is not a directory"));
        }
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(folder)) {
            throw storeFailure(new IOException("artifact parent escapes the conversation folder"));
        }
    }

    private static void requireOrdinaryTarget(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw storeFailure(new IOException("artifact target is not a regular file"));
        }
    }

    private static void writeWithSecureDirectory(SecureDirectoryStream<Path> directory, Path targetName, byte[] content)
            throws IOException {
        Path temporaryName = Path.of(".artifact-" + UUID.randomUUID() + ".tmp");
        boolean temporaryCreated = false;
        try {
            Set<OpenOption> options =
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = directory.newByteChannel(temporaryName, options)) {
                temporaryCreated = true;
                writeFully(channel, content);
            }
            requireOrdinaryTargetInDirectory(directory, targetName);
            directory.move(temporaryName, directory, targetName);
            temporaryCreated = false;
        } finally {
            if (temporaryCreated) {
                deleteSecurely(directory, temporaryName);
            }
        }
    }

    private static void writeWithAtomicMove(
            Path parent, Path target, Path folder, byte[] content, ArtifactAtomicMover atomicMover) throws IOException {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".artifact-", ".tmp");
            try (SeekableByteChannel channel = Files.newByteChannel(
                    temporary, EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                writeFully(channel, content);
            }
            verifyParent(parent, folder);
            requireOrdinaryTarget(target);
            atomicMover.move(temporary, target);
            temporary = null;
        } catch (AtomicMoveNotSupportedException ex) {
            throw ex;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 저장 실패의 원인을 임시 파일 정리 실패로 바꾸지 않는다.
                }
            }
        }
    }

    private static void requireOrdinaryTargetInDirectory(SecureDirectoryStream<Path> directory, Path target)
            throws IOException {
        BasicFileAttributeView view =
                directory.getFileAttributeView(target, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("could not inspect artifact target");
        }
        try {
            BasicFileAttributes attributes = view.readAttributes();
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw storeFailure(new IOException("artifact target is not a regular file"));
            }
        } catch (NoSuchFileException ignored) {
            // 대상이 없으면 새 파일로 바꿀 수 있다.
        }
    }

    private static void writeFully(SeekableByteChannel channel, byte[] content) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void deleteSecurely(SecureDirectoryStream<Path> directory, Path temporary) {
        try {
            directory.deleteFile(temporary);
        } catch (IOException ignored) {
            // 저장 실패의 원인을 임시 파일 정리 실패로 바꾸지 않는다.
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void warnUnsupportedSecureDirectoryOnce() {
        if (UNSUPPORTED_SECURE_DIRECTORY_WARNING_LOGGED.compareAndSet(false, true)) {
            log.warn("SecureDirectoryStream is unavailable; using checked atomic artifact replacement");
        }
    }

    private static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static ApiException storeFailure(Exception cause) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, "could not store artifact", cause);
    }

    private static String relativeOf(Path folder, Path file) {
        return StreamSupport.stream(folder.relativize(file).spliterator(), false)
                .map(Path::toString)
                .collect(Collectors.joining("/"));
    }

    private static Long conversationIdOf(String name) {
        try {
            return Long.valueOf(name);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingSlash(String path) {
        String stripped = path.strip();
        while (stripped.length() > 1 && stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
