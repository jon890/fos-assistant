package com.bifos.assistant.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 보관 기간 정리가 판정한 뒤 바뀐 대화 폴더의 파일을 지우지 않는지 확인한다. */
class ArtifactStoreCleanupTest {

    private static final ArtifactCleanupProbe NO_PROBE = conversationId -> {};

    @TempDir
    Path root;

    @Test
    @DisplayName("변경 없는 만료 대화의 파일을 모두 지운다")
    void deletesAllFilesOfUnchangedExpiredConversation() throws IOException {
        ArtifactStore store = store(NO_PROBE);
        writeOld(7L, "a/index.html", "옛 본문");
        writeOld(7L, "a/photo.png", "png");

        List<ArtifactRemoved> removed = store.deleteOlderThan(cutoff());

        assertThat(removed)
                .extracting(ArtifactRemoved::conversationId, ArtifactRemoved::path)
                .containsExactlyInAnyOrder(tuple(7L, "a/index.html"), tuple(7L, "a/photo.png"));
        assertThat(root.resolve("7/a/index.html")).doesNotExist();
        assertThat(root.resolve("7/a/photo.png")).doesNotExist();
    }

    @Test
    @DisplayName("판정 뒤 같은 경로를 MCP 쓰기로 교체하면 그 폴더를 지우지 않는다")
    void keepsFolderWhenSamePathIsReplacedAfterJudgement() throws IOException {
        AtomicReference<ArtifactStore> holder = new AtomicReference<>();
        ArtifactStore store = store(
                conversationId -> holder.get().write(7L, "a/index.html", "새 본문".getBytes(StandardCharsets.UTF_8)));
        holder.set(store);
        writeOld(7L, "a/index.html", "옛 본문");
        writeOld(7L, "a/photo.png", "png");

        List<ArtifactRemoved> removed = store.deleteOlderThan(cutoff());

        assertThat(removed).isEmpty();
        assertThat(Files.readString(root.resolve("7/a/index.html"))).isEqualTo("새 본문");
        assertThat(root.resolve("7/a/photo.png")).exists();
    }

    @Test
    @DisplayName("판정 뒤 새 HTML 이 더해지면 옛 사진을 남긴다")
    void keepsOldImageWhenNewHtmlIsAddedAfterJudgement() throws IOException {
        ArtifactStore store = store(conversationId -> {
            try {
                Files.writeString(root.resolve("7/a/new.html"), "새 HTML");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        writeOld(7L, "a/index.html", "옛 본문");
        writeOld(7L, "a/photo.png", "png");

        List<ArtifactRemoved> removed = store.deleteOlderThan(cutoff());

        assertThat(removed).extracting(ArtifactRemoved::path).doesNotContain("a/photo.png");
        assertThat(root.resolve("7/a/photo.png")).exists();
        assertThat(root.resolve("7/a/new.html")).exists();
    }

    @Test
    @DisplayName("정리 중 시작한 MCP 쓰기는 정리가 끝날 때까지 기다린다")
    void mcpWriteDuringCleanupWaitsUntilCleanupEnds() throws Exception {
        AtomicReference<ArtifactStore> holder = new AtomicReference<>();
        AtomicReference<CompletableFuture<Long>> lateWrite = new AtomicReference<>();
        ArtifactStore store = store(conversationId -> {
            CompletableFuture<Long> future = CompletableFuture.supplyAsync(
                    () -> holder.get().write(7L, "a/late.html", "늦은 본문".getBytes(StandardCharsets.UTF_8)));
            lateWrite.set(future);
            assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                    .as("정리가 잠금을 잡은 동안 다른 스레드의 쓰기가 끝나면 안 된다")
                    .isInstanceOf(TimeoutException.class);
        });
        holder.set(store);
        writeOld(7L, "a/index.html", "옛 본문");
        writeOld(7L, "a/photo.png", "png");

        store.deleteOlderThan(cutoff());
        lateWrite.get().get(5, TimeUnit.SECONDS);

        assertThat(Files.readString(root.resolve("7/a/late.html"))).isEqualTo("늦은 본문");
    }

    @Test
    @DisplayName("다른 대화의 정리는 최근에 바뀐 대화의 영향을 받지 않는다")
    void cleansOtherConversationIndependently() throws IOException {
        ArtifactStore store = store(NO_PROBE);
        Path recent = root.resolve("7/a/index.html");
        Files.createDirectories(recent.getParent());
        Files.writeString(recent, "최근 본문");
        writeOld(8L, "b/index.html", "옛 본문");

        List<ArtifactRemoved> removed = store.deleteOlderThan(cutoff());

        assertThat(removed)
                .extracting(ArtifactRemoved::conversationId, ArtifactRemoved::path)
                .containsExactly(tuple(8L, "b/index.html"));
        assertThat(recent).exists();
        assertThat(root.resolve("8/b/index.html")).doesNotExist();
    }

    private ArtifactStore store(ArtifactCleanupProbe probe) {
        ArtifactProperties properties = new ArtifactProperties(root.toString(), "/agent/artifacts", 30);
        ArtifactAtomicMover mover = (source, target) ->
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new ArtifactStore(properties, false, mover, probe);
    }

    private void writeOld(Long conversationId, String relativePath, String body) throws IOException {
        Path file = root.resolve(String.valueOf(conversationId)).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(40))));
    }

    private static Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(30));
    }
}
