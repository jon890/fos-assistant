package com.bifos.assistant.usage.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 홈서버가 가격표를 새로 받아 오면 재기동 없이 다음 실행부터 새 가격을 쓰는지 본다.
 *
 * <p>실제로 가격표를 기동할 때만 읽어, 새로 받은 파일에 있는 모델의 금액이 재기동 전까지 비어 있었다.
 */
class ModelsDevPriceCatalogReloadTest {

    private static final Instant FIRST = Instant.parse("2026-09-17T04:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-09-28T04:00:00Z");

    @TempDir
    Path dir;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-28T00:00:00Z"));

    @Test
    void 파일이_바뀌면_확인_간격이_지난_뒤_새_가격과_버전을_쓴다() throws IOException {
        Path file = write(catalog("old-model"), FIRST);
        ModelsDevPriceCatalog catalog = catalogOf(file);
        assertThat(catalog.find("openai", "new-model")).isEmpty();

        write(catalog("new-model"), SECOND);
        assertThat(catalog.find("openai", "new-model")).as("간격 안에서는 디스크를 다시 보지 않는다").isEmpty();

        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));
        assertThat(catalog.find("openai", "new-model")).isPresent();
        assertThat(catalog.version()).isEqualTo("models.dev@2026-09-28");
    }

    @Test
    void 새_파일을_읽지_못하면_이전_가격을_계속_쓴다() throws IOException {
        Path file = write(catalog("old-model"), FIRST);
        ModelsDevPriceCatalog catalog = catalogOf(file);

        write("{ 받다가 끊긴 파일", SECOND);
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));

        assertThat(catalog.find("openai", "old-model")).isPresent();
        assertThat(catalog.version()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    void 읽기에_실패한_파일은_수정_시각이_같아도_다음_확인_때_다시_읽는다() throws IOException {
        Path file = write(catalog("old-model"), FIRST);
        ModelsDevPriceCatalog catalog = catalogOf(file);
        // 그 자리에 디렉터리를 두면 수정 시각은 읽히고 Files.readString 만 입출력 오류로 실패한다.
        Files.delete(file);
        Files.createDirectory(file);
        Files.setLastModifiedTime(file, FileTime.from(SECOND));
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));
        assertThat(catalog.find("openai", "old-model")).as("읽지 못하는 동안에도 이전 가격을 쓴다").isPresent();

        Files.delete(file);
        write(catalog("new-model"), SECOND);
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));

        assertThat(catalog.find("openai", "new-model")).as("수정 시각이 같아도 입출력 오류였으므로 다시 읽는다").isPresent();
    }

    @Test
    void 사라진_파일이_돌아오면_읽는다() throws IOException {
        Path file = write(catalog("old-model"), FIRST);
        ModelsDevPriceCatalog catalog = catalogOf(file);
        Files.delete(file);
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));
        assertThat(catalog.find("openai", "old-model")).as("사라진 동안에도 이전 가격을 쓴다").isPresent();

        write(catalog("new-model"), SECOND);
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));

        assertThat(catalog.find("openai", "new-model")).isPresent();
    }

    @Test
    void 내용이_틀린_파일을_고쳐_다시_쓰면_읽는다() throws IOException {
        Path file = write(catalog("old-model"), FIRST);
        ModelsDevPriceCatalog catalog = catalogOf(file);
        write("{ 받다가 끊긴 파일", SECOND);
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));
        catalog.find("openai", "old-model");

        write(catalog("new-model"), SECOND.plusSeconds(60));
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));

        assertThat(catalog.find("openai", "new-model")).isPresent();
    }

    @Test
    void 가격이_하나도_없는_파일은_받아들이지_않는다() throws IOException {
        Path file = write(catalog("old-model"), FIRST);
        ModelsDevPriceCatalog catalog = catalogOf(file);

        write("{}", SECOND);
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));

        assertThat(catalog.find("openai", "old-model")).isPresent();
    }

    @Test
    void 기동할_때_없던_파일이_나중에_생기면_읽는다() throws IOException {
        Path file = dir.resolve("catalog.json");
        ModelsDevPriceCatalog catalog = catalogOf(file);
        assertThat(catalog.isAvailable()).isFalse();

        write(catalog("new-model"), SECOND);
        now.set(now.get().plus(ModelsDevPriceCatalog.CHECK_INTERVAL));

        assertThat(catalog.isAvailable()).isTrue();
        assertThat(catalog.find("openai", "new-model")).isPresent();
    }

    private ModelsDevPriceCatalog catalogOf(Path file) {
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new ModelsDevPriceCatalog(new PricingProperties(file.toString()), clock);
    }

    private Path write(String body, Instant modifiedAt) throws IOException {
        Path file = dir.resolve("catalog.json");
        Files.writeString(file, body);
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt));
        return file;
    }

    private static String catalog(String model) {
        return """
                { "openai": { "models": { "%s": { "cost": { "input": 2, "output": 10 } } } } }
                """.formatted(model);
    }
}
