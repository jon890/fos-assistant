package com.bifos.assistant.chat.infra;

import static com.bifos.assistant.chat.infra.ArtifactPathPolicy.validation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 대화마다 둔 결과물 폴더를 만들고 훑고 파일을 찾고 지운다.
 *
 * <p>경로와 링크 판정은 {@link ArtifactPathPolicy} 에 모으고, 이 클래스는 대화별 잠금과 파일 훑기,
 * 보관 기간 정리를 조정한다. 폴더는 {@code {root}/{대화 번호}} 이고 그 안은 에이전트가 정한다.
 * 근거는 ADR-027 에 있다.
 */
@Component
@Slf4j
public class ArtifactStore {

    static final String HTML = ArtifactPathPolicy.HTML;
    private static final int LOCK_COUNT = 64;

    private final Path root;
    private final ArtifactPathPolicy paths;
    private final String agentRoot;
    private final ArtifactFileWriter fileWriter;
    private final ArtifactCleanupProbe cleanupProbe;

    /**
     * 대화 번호로 나눈 잠금. MCP 쓰기와 보관 기간 정리가 같은 대화 폴더에서 겹치지 않게 한다.
     *
     * <p>대화마다 잠금을 만들면 대화 수만큼 쌓이므로 고정된 개수에서 고른다. 같은 잠금을 쓰는 다른 대화끼리는 서로
     * 기다린다.
     */
    private final ReentrantLock[] conversationLocks = new ReentrantLock[LOCK_COUNT];

    @Autowired
    public ArtifactStore(ArtifactProperties properties) {
        this(properties, false, ArtifactFileWriter::atomicMove);
    }

    /** Linux에서도 링크 재검사와 원자 교체 대체 경로를 검사할 때 쓴다. */
    ArtifactStore(ArtifactProperties properties, boolean forceAtomicMoveFallback) {
        this(properties, forceAtomicMoveFallback, ArtifactFileWriter::atomicMove);
    }

    /** 파일 시스템의 원자 교체 실패를 결정적으로 검사할 때 쓴다. */
    ArtifactStore(ArtifactProperties properties, boolean forceAtomicMoveFallback, ArtifactAtomicMover atomicMover) {
        this(properties, forceAtomicMoveFallback, atomicMover, conversationId -> {});
    }

    /** 정리가 판정한 뒤 지우기 전에 쓰기가 끼어드는 경우를 결정적으로 검사할 때 쓴다. */
    ArtifactStore(
            ArtifactProperties properties,
            boolean forceAtomicMoveFallback,
            ArtifactAtomicMover atomicMover,
            ArtifactCleanupProbe cleanupProbe) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.paths = new ArtifactPathPolicy(root);
        this.agentRoot = stripTrailingSlash(properties.agentRoot());
        this.fileWriter = new ArtifactFileWriter(paths, forceAtomicMoveFallback, atomicMover);
        this.cleanupProbe = cleanupProbe;
        for (int index = 0; index < LOCK_COUNT; index++) {
            conversationLocks[index] = new ReentrantLock();
        }
    }

    /** 확장자로 정한 형식. 내주지 않는 확장자면 빈 값이다. 대소문자는 가리지 않는다. */
    public static Optional<String> contentTypeOf(String relativePath) {
        return ArtifactPathPolicy.contentTypeOf(relativePath);
    }

    /**
     * 대화 폴더를 만든다. 이미 있으면 그대로 둔다.
     *
     * <p>만들지 못해도 turn 을 멈추지 않는다. 에이전트가 파일을 쓰지 못할 뿐 대화는 이어진다. 경고 로그로 남긴다.
     */
    public Path ensureFolder(Long conversationId) {
        Path folder = paths.folderOf(conversationId);
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
        Path folder = paths.folderOf(conversationId);
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
        return paths.resolveInside(conversationId, relativePath);
    }

    /**
     * 그 대화 폴더에 그 경로의 파일이 없다고 확인되면 참이다.
     *
     * <p>파일이 없는데 지운 표시가 없는 행을 맞출 때 쓴다. 결과물 루트가 디렉터리가 아니거나 대화 폴더가 없으면
     * 거짓이다. 붙지 않은 루트나 빈 디렉터리로 바뀐 루트를 보고 모든 행을 지웠다고 적지 않으려는 것이다. 정리는 대화
     * 폴더를 지우지 않으므로 폴더가 없다는 것은 루트가 정상이 아니라는 뜻이다. 빈 경로, 절대 경로, 정규화하면 대화 폴더 밖으로 나가는 경로,
     * 경로로 쓸 수 없는 글자가 섞인 경로도 거짓이다. 모르는 것을 지웠다고 적지 않는다. 없다고 확인할 수 없는 경우도
     * 거짓이 되도록 {@link Files#notExists} 로 본다.
     */
    public boolean isMissing(Long conversationId, String relativePath) {
        return paths.isMissing(conversationId, relativePath);
    }

    /**
     * 결과물을 쓸 최종 경로를 판정하고 필요한 부모 폴더를 만든다.
     *
     * <p>기존 읽기 경로와 달리 아직 없는 최종 파일도 받는다. 대화 폴더와 이미 있던 부모는 링크를 따라가지
     * 않고 확인하고, 새 부모는 하나씩 만든 직후 실제 경로를 다시 확인한다.
     */
    public Path resolveForWrite(Long conversationId, String relativePath) {
        return paths.resolveForWrite(conversationId, relativePath);
    }

    /** 파일 시스템을 열지 않고 결과물 쓰기 경로의 형식과 확장자만 검사한다. */
    public static void requireWritablePath(String relativePath) {
        ArtifactPathPolicy.requireWritablePath(relativePath);
    }

    /**
     * 검증한 대화 폴더 안에서 임시 파일을 완성한 뒤 원자적으로 바꾼다.
     *
     * <p>원자 교체를 지원하지 않는 파일 시스템에서는 기존 파일을 직접 덮지 않는다. 실패한 저장이 기존
     * 결과물을 망가뜨리지 않게 하기 위해서다.
     *
     * <p>경로 판정부터 교체까지 그 대화의 잠금 안에서 돈다. 보관 기간 정리가 같은 폴더를 판정하고 지우는 사이에
     * 끼어들지 않게 하려는 것이다.
     */
    public long write(Long conversationId, String relativePath, byte[] content) {
        if (content == null) {
            throw validation("artifact content is required");
        }
        ReentrantLock lock = lockOf(conversationId);
        lock.lock();
        try {
            return fileWriter.writeLocked(conversationId, relativePath, content);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 대화 폴더마다 마지막으로 바뀐 때가 {@code cutoff} 보다 앞섰는지 보고, 앞섰으면 그 폴더의 파일을 지운다.
     *
     * <p>대화 번호 이름의 폴더 안에 있는 파일만 지운다. 폴더 하나를 다루는 동안 그 대화의 잠금을 잡고, 판정도 잠금
     * 안에서 새로 훑어 한다. 판정 뒤 바뀐 파일을 옛 판정으로 지우지 않으려는 것이다. 한 파일이나 한 대화가 실패해도
     * 나머지를 계속한다. 하나 때문에 그날 치가 통째로 멈추면 디스크가 계속 찬다. 빈 폴더는 남긴다.
     *
     * <p>잠금 밖에서 Hermes 가 직접 쓰는 경우의 보호 경계는 {@code docs/backend/artifact.md} 의 「보관 기간이 지난
     * 파일을 지울 때」 에 있다.
     *
     * @return 지운 파일들의 대화 번호와 상대 경로
     */
    public List<ArtifactRemoved> deleteOlderThan(Instant cutoff) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Long> conversationIds = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                Long conversationId = conversationIdOf(entry.getFileName().toString());
                if (conversationId != null && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    conversationIds.add(conversationId);
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("could not list the artifact root", ex);
            return List.of();
        }
        List<ArtifactRemoved> removed = new ArrayList<>();
        for (Long conversationId : conversationIds) {
            ReentrantLock lock = lockOf(conversationId);
            lock.lock();
            try {
                deleteExpiredFolder(conversationId, cutoff, removed);
            } catch (RuntimeException ex) {
                log.warn("could not clean up an expired artifact folder conversationId={}", conversationId, ex);
            } finally {
                lock.unlock();
            }
        }
        return removed;
    }

    /**
     * 잠금을 잡은 채 한 대화 폴더를 판정하고 지운다. 지운 파일은 {@code removed} 에 더한다.
     *
     * <p>보관 기간은 대화 폴더 단위로 센다. 파일마다 세면 다음 turn 이 HTML 만 고쳤을 때 그 HTML 이 부르는 옛 사진이
     * 먼저 지워져 사진이 깨진 초안이 남는다. HTML 을 먼저 지우고, 사진과 CSS 를 지우기 전에 폴더를 다시 훑는다. 그 사이
     * 새 HTML 이 생겼으면 그것이 옛 사진을 부를 수 있어 멈춘다.
     */
    private void deleteExpiredFolder(Long conversationId, Instant cutoff, List<ArtifactRemoved> removed) {
        Path folder = paths.folderOf(conversationId);
        List<WalkedFile> judged = regularFilesUnder(folder);
        if (judged.isEmpty() || !allBefore(judged, cutoff)) {
            return;
        }
        cleanupProbe.afterJudged(conversationId);
        Set<Path> deleted = new HashSet<>();
        for (WalkedFile file : judged) {
            if (isHtml(file.path())
                    && !deleteIfStillExpired(conversationId, folder, file.path(), cutoff, deleted, removed)) {
                log.info("stopped cleaning an artifact folder, an html file changed conversationId={}", conversationId);
                return;
            }
        }
        List<WalkedFile> rescanned = regularFilesUnder(folder);
        if (!allBefore(rescanned, cutoff)) {
            log.info(
                    "stopped cleaning an artifact folder, a file changed before removing the rest conversationId={}",
                    conversationId);
            return;
        }
        for (WalkedFile file : rescanned) {
            if (!deleted.contains(file.path())
                    && !deleteIfStillExpired(conversationId, folder, file.path(), cutoff, deleted, removed)) {
                log.info("stopped cleaning an artifact folder, a file changed conversationId={}", conversationId);
                return;
            }
        }
    }

    /**
     * 지우기 직전 수정 시각을 다시 읽어 아직 기간을 넘겼을 때만 지운다.
     *
     * @return 기간 안으로 바뀌어 그 폴더의 삭제를 멈춰야 하면 {@code false}
     */
    private static boolean deleteIfStillExpired(
            Long conversationId,
            Path folder,
            Path file,
            Instant cutoff,
            Set<Path> deleted,
            List<ArtifactRemoved> removed) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                return false;
            }
        } catch (NoSuchFileException ex) {
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("could not read an expired artifact file conversationId={}", conversationId, ex);
            return true;
        }
        try {
            if (Files.deleteIfExists(file)) {
                deleted.add(file);
                removed.add(new ArtifactRemoved(conversationId, relativeOf(folder, file)));
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("could not delete an expired artifact file conversationId={}", conversationId, ex);
        }
        return true;
    }

    private static boolean allBefore(List<WalkedFile> files, Instant cutoff) {
        return files.stream().allMatch(file -> file.modified().isBefore(cutoff));
    }

    private static boolean isHtml(Path file) {
        return HTML.equals(extensionOf(file.getFileName().toString()));
    }

    private ReentrantLock lockOf(Long conversationId) {
        // 대화 번호가 없는 쓰기는 판정에서 거절되지만, 그 전에 잠금을 고르다 실패하지 않게 Objects.hashCode 를 쓴다.
        return conversationLocks[Math.floorMod(Objects.hashCode(conversationId), LOCK_COUNT)];
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
        return ArtifactPathPolicy.extensionOf(path);
    }

    private static String stripTrailingSlash(String path) {
        String stripped = path.strip();
        while (stripped.length() > 1 && stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
