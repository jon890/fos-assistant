package com.bifos.assistant.chat.infra;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/** 결과물의 경로와 허용 확장자, 대화 폴더 경계와 심볼릭 링크를 판정한다. */
@Slf4j(topic = "com.bifos.assistant.chat.infra.ArtifactStore")
final class ArtifactPathPolicy {

    static final String HTML = "html";

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

    ArtifactPathPolicy(Path root) {
        this.root = root;
    }

    static Optional<String> contentTypeOf(String relativePath) {
        return Optional.ofNullable(CONTENT_TYPES.get(extensionOf(relativePath)));
    }

    Optional<Path> resolveInside(Long conversationId, String relativePath) {
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

    boolean isMissing(Long conversationId, String relativePath) {
        if (conversationId == null || relativePath == null || relativePath.isBlank() || !Files.isDirectory(root)) {
            return false;
        }
        try {
            Path requested = Path.of(relativePath);
            if (requested.isAbsolute()) {
                return false;
            }
            Path folder = folderOf(conversationId);
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path file = folder.resolve(requested).normalize();
            if (!file.startsWith(folder) || file.equals(folder)) {
                return false;
            }
            return Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException ex) {
            log.debug("could not check an artifact path conversationId={}", conversationId, ex);
            return false;
        }
    }

    Path resolveForWrite(Long conversationId, String relativePath) {
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

    static void requireWritablePath(String relativePath) {
        writableParts(relativePath);
    }

    Path folderOf(Long conversationId) {
        return root.resolve(String.valueOf(conversationId));
    }

    Path checkedConversationFolder(Long conversationId) throws IOException {
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

    static void verifyParent(Path parent, Path folder) throws IOException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw storeFailure(new IOException("artifact parent is not a directory"));
        }
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(folder)) {
            throw storeFailure(new IOException("artifact parent escapes the conversation folder"));
        }
    }

    static void requireOrdinaryTarget(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw storeFailure(new IOException("artifact target is not a regular file"));
        }
    }

    static void requireOrdinaryTargetInDirectory(SecureDirectoryStream<Path> directory, Path target)
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

    static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    static ApiException storeFailure(Exception cause) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, "could not store artifact", cause);
    }

    static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
