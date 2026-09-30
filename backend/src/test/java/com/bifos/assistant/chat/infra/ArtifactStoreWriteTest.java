package com.bifos.assistant.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.ArtifactProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

/** 결과물 쓰기가 대화 폴더를 벗어나지 않고 기존 파일을 원자적으로 바꾸는지 확인한다. */
class ArtifactStoreWriteTest {

    @Test
    @DisplayName("HTML과 CSS를 대화 폴더에 쓰고 UTF 8 크기를 돌려준다")
    void writesHtmlAndCssToConversationFolderAndReturnsUtf8Size(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);

        long htmlSize = store.write(10L, "test/index.html", "한글".getBytes(StandardCharsets.UTF_8));
        long cssSize = store.write(10L, "test/style.css", "p{}".getBytes(StandardCharsets.UTF_8));

        assertThat(htmlSize).isEqualTo(6L);
        assertThat(cssSize).isEqualTo(3L);
        assertThat(Files.readString(root.resolve("10/test/index.html"))).isEqualTo("한글");
        assertThat(Files.readString(root.resolve("10/test/style.css"))).isEqualTo("p{}");
    }

    @Test
    @DisplayName("같은 경로를 다시 쓰면 새 본문으로 교체한다")
    void replacesWithNewBodyWhenSamePathIsWrittenAgain(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);
        store.write(10L, "index.html", "이전".getBytes(StandardCharsets.UTF_8));

        long size = store.write(10L, "index.html", "최신 본문".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(root.resolve("10/index.html"))).isEqualTo("최신 본문");
        assertThat(size).isEqualTo("최신 본문".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("강제한 ATOMIC MOVE 대체 경로도 저장한다")
    void storesOnFallbackPathWhenAtomicMoveIsForced(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);

        store.write(10L, "nested/index.html", "대체 경로".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(root.resolve("10/nested/index.html"))).isEqualTo("대체 경로");
    }

    @Test
    @DisplayName("기본 경로도 저장한다")
    void storesOnDefaultPathToo(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, false);

        store.write(10L, "index.html", "본문".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(root.resolve("10/index.html"))).isEqualTo("본문");
    }

    @Test
    @DisplayName("Linux에서는 SecureDirectoryStream 경로를 쓴다")
    void usesSecureDirectoryStreamPathOnLinux(@TempDir Path root) throws IOException {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        ArtifactStore store = store(root, false);
        Path target = store.resolveForWrite(10L, "index.html");

        try (DirectoryStream<Path> directory = Files.newDirectoryStream(target.getParent())) {
            assertThat(directory).isInstanceOf(SecureDirectoryStream.class);
        }
        store.write(10L, "index.html", "본문".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(target)).isEqualTo("본문");
    }

    @Test
    @DisplayName("원자 교체가 실패하면 기존 파일을 보존하고 임시 파일을 지운다")
    void keepsExistingFileAndDeletesTempWhenAtomicReplaceFails(@TempDir Path root) throws IOException {
        ArtifactStore initial = store(root, true);
        initial.write(10L, "index.html", "이전 본문".getBytes(StandardCharsets.UTF_8));
        ArtifactStore failing = new ArtifactStore(new ArtifactProperties(root.toString(), "/agent/artifacts", 30),
                true, (source, target) -> { throw new IOException("forced replacement failure"); });

        assertThatThrownBy(() -> failing.write(10L, "index.html", "새 본문".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        Path folder = root.resolve("10");
        assertThat(Files.readString(folder.resolve("index.html"))).isEqualTo("이전 본문");
        try (var files = Files.list(folder)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".artifact-"));
        }
    }

    @Test
    @DisplayName("위험한 경로는 거절하고 폴더 밖을 바꾸지 않는다")
    void rejectsDangerousPathWithoutChangingOutsideFolder(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);
        Path outside = root.resolve("outside.html");
        Files.writeString(outside, "보존");

        for (String path : List.of("", "/outside.html", "../outside.html", "a\\b.html", "a\0b.html", "a.svg",
                "a".repeat(501) + ".html")) {
            assertValidation(() -> store.write(10L, path, new byte[0]));
        }

        assertThat(Files.readString(outside)).isEqualTo("보존");
        assertThat(root.resolve("10")).doesNotExist();
    }

    @Test
    @DisplayName("대화 폴더와 부모 폴더와 대상 링크를 거절한다")
    void rejectsConversationFolderParentFolderAndTargetLink(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.html"), "비밀");

        Files.createSymbolicLink(root.resolve("10"), outside);
        assertStoreFailure(() -> store.write(10L, "index.html", new byte[0]));
        Files.delete(root.resolve("10"));

        Files.createDirectories(root.resolve("10"));
        Files.createSymbolicLink(root.resolve("10/parent"), outside);
        assertStoreFailure(() -> store.write(10L, "parent/index.html", new byte[0]));

        Files.createSymbolicLink(root.resolve("10/index.html"), outside.resolve("secret.html"));
        assertStoreFailure(() -> store.write(10L, "index.html", new byte[0]));
        assertThat(Files.readString(outside.resolve("secret.html"))).isEqualTo("비밀");
    }

    @Test
    @DisplayName("저장소 루트를 만들지 못하면 저장 실패를 돌린다")
    void failsSaveWhenStoreRootCannotBeCreated(@TempDir Path root) throws IOException {
        Path blockedRoot = root.resolve("blocked");
        Files.writeString(blockedRoot, "기존 파일");
        ArtifactStore store = store(blockedRoot, true);

        assertStoreFailure(() -> store.write(10L, "index.html", new byte[0]));
        assertThat(Files.readString(blockedRoot)).isEqualTo("기존 파일");
    }

    private static ArtifactStore store(Path root, boolean forceAtomicMoveFallback) {
        return new ArtifactStore(new ArtifactProperties(root.toString(), "/agent/artifacts", 30), forceAtomicMoveFallback);
    }

    private static void assertValidation(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private static void assertStoreFailure(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
