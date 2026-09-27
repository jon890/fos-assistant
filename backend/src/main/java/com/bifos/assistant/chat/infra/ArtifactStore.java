package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.application.ArtifactProperties;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 대화마다 둔 결과물 폴더를 만들고 훑고 파일을 찾고 지운다.
 *
 * <p>경로를 만드는 규칙이 이 클래스 하나에만 있다. 폴더는 {@code {root}/{대화 번호}} 이고 그 안은 에이전트가
 * 정한다. 대화 폴더 밖인지 판정하는 것도 여기서 한다. 규칙이 흩어지면 판정하는 쪽이나 지우는 쪽이 놓친다.
 * 근거는 ADR-027 에 있다.
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private static final String HTML = "html";

    /**
     * 내주는 확장자와 그 형식. 이 밖의 파일은 폴더에 있어도 내주지 않는다.
     *
     * <p>SVG 는 스크립트를 품을 수 있어 받지 않는다.
     */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            HTML, "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    private final Path root;
    private final String agentRoot;

    public ArtifactStore(ArtifactProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.agentRoot = stripTrailingSlash(properties.agentRoot());
    }

    /**
     * 폴더 안에서 찾은 파일 하나다.
     *
     * @param path 대화 폴더 안의 상대 경로. {@code /} 로 나눈다
     * @param byteSize 찾았을 때의 크기
     */
    public record FoundFile(String path, long byteSize) {
    }

    /**
     * 보관 기간이 지나 지운 파일 하나다.
     *
     * @param path 대화 폴더 안의 상대 경로. {@code /} 로 나눈다
     */
    public record Removed(Long conversationId, String path) {

        public boolean isHtml() {
            return HTML.equals(extensionOf(path));
        }
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
    public List<FoundFile> changedHtmlSince(Long conversationId, Instant since) {
        Path folder = folderOf(conversationId);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<FoundFile> found = new ArrayList<>();
        for (WalkedFile file : regularFilesUnder(folder)) {
            if (HTML.equals(extensionOf(file.path().getFileName().toString()))
                    && !file.modified().isBefore(since)) {
                found.add(new FoundFile(relativeOf(folder, file.path()), file.byteSize()));
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
        if (relativePath == null || relativePath.isBlank() || contentTypeOf(relativePath).isEmpty()) {
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
     * 뿌리 아래를 걸어 마지막으로 바뀐 때가 {@code cutoff} 보다 앞선 파일을 지운다.
     *
     * <p>대화 번호 이름의 폴더 안에 있는 파일만 지운다. 한 파일이 실패해도 나머지를 계속한다. 하나 때문에 그날
     * 치가 통째로 멈추면 디스크가 계속 찬다. 빈 폴더는 남긴다.
     *
     * @return 지운 파일들의 대화 번호와 상대 경로
     */
    public List<Removed> deleteOlderThan(Instant cutoff) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Removed> removed = new ArrayList<>();
        for (WalkedFile walked : regularFilesUnder(root)) {
            Path file = walked.path();
            Path relative = root.relativize(file);
            if (relative.getNameCount() < 2) {
                continue;
            }
            Long conversationId = conversationIdOf(relative.getName(0).toString());
            if (conversationId == null) {
                continue;
            }
            if (!walked.modified().isBefore(cutoff)) {
                continue;
            }
            try {
                Files.deleteIfExists(file);
                removed.add(new Removed(conversationId, relativeOf(root.resolve(relative.getName(0)), file)));
            } catch (IOException | RuntimeException ex) {
                log.warn("could not delete an expired artifact file conversationId={}", conversationId, ex);
            }
        }
        return removed;
    }

    /** 걸음에서 만난 일반 파일 하나와 그때 읽은 속성이다. */
    private record WalkedFile(Path path, Instant modified, long byteSize) {
    }

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
                        files.add(new WalkedFile(file, attributes.lastModifiedTime().toInstant(), attributes.size()));
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

    private static String extensionOf(String path) {
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
