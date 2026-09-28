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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

/** 결과물 쓰기가 대화 폴더를 벗어나지 않고 기존 파일을 원자적으로 바꾸는지 확인한다. */
class ArtifactStoreWriteTest {

    @Test
    void HTML과_CSS를_대화_폴더에_쓰고_UTF_8_크기를_돌려준다(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);

        long htmlSize = store.write(10L, "test/index.html", "한글".getBytes(StandardCharsets.UTF_8));
        long cssSize = store.write(10L, "test/style.css", "p{}".getBytes(StandardCharsets.UTF_8));

        assertThat(htmlSize).isEqualTo(6L);
        assertThat(cssSize).isEqualTo(3L);
        assertThat(Files.readString(root.resolve("10/test/index.html"))).isEqualTo("한글");
        assertThat(Files.readString(root.resolve("10/test/style.css"))).isEqualTo("p{}");
    }

    @Test
    void 같은_경로를_다시_쓰면_새_본문으로_교체한다(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);
        store.write(10L, "index.html", "이전".getBytes(StandardCharsets.UTF_8));

        long size = store.write(10L, "index.html", "최신 본문".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(root.resolve("10/index.html"))).isEqualTo("최신 본문");
        assertThat(size).isEqualTo("최신 본문".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void 강제한_ATOMIC_MOVE_대체_경로도_저장한다(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);

        store.write(10L, "nested/index.html", "대체 경로".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(root.resolve("10/nested/index.html"))).isEqualTo("대체 경로");
    }

    @Test
    void 기본_경로도_저장한다(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, false);

        store.write(10L, "index.html", "본문".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(root.resolve("10/index.html"))).isEqualTo("본문");
    }

    @Test
    void Linux에서는_SecureDirectoryStream_경로를_쓴다(@TempDir Path root) throws IOException {
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
    void 원자_교체가_실패하면_기존_파일을_보존하고_임시_파일을_지운다(@TempDir Path root) throws IOException {
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
    void 위험한_경로는_거절하고_폴더_밖을_바꾸지_않는다(@TempDir Path root) throws IOException {
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
    void 대화_폴더와_부모_폴더와_대상_링크를_거절한다(@TempDir Path root) throws IOException {
        ArtifactStore store = store(root, true);
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.html"), "비밀");

        Files.createSymbolicLink(root.resolve("10"), outside);
        assertValidation(() -> store.write(10L, "index.html", new byte[0]));
        Files.delete(root.resolve("10"));

        Files.createDirectories(root.resolve("10"));
        Files.createSymbolicLink(root.resolve("10/parent"), outside);
        assertValidation(() -> store.write(10L, "parent/index.html", new byte[0]));

        Files.createSymbolicLink(root.resolve("10/index.html"), outside.resolve("secret.html"));
        assertValidation(() -> store.write(10L, "index.html", new byte[0]));
        assertThat(Files.readString(outside.resolve("secret.html"))).isEqualTo("비밀");
    }

    private static ArtifactStore store(Path root, boolean forceAtomicMoveFallback) {
        return new ArtifactStore(new ArtifactProperties(root.toString(), "/agent/artifacts", 30), forceAtomicMoveFallback);
    }

    private static void assertValidation(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
