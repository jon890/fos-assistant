package com.bifos.assistant.usage.infra;

import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.domain.CatalogPrice;
import com.bifos.assistant.usage.domain.ModelPrice;
import com.bifos.assistant.usage.domain.PriceCatalog;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 홈서버가 받아 둔 models.dev 카탈로그에서 읽은 가격이다.
 *
 * <p>읽은 결과를 메모리에 두고, 조회할 때 {@link #CHECK_INTERVAL} 에 한 번만 파일의 수정 시각을 본다.
 * 시각이 바뀌었으면 다시 읽는다. 가격표는 홈서버가 주기적으로 새로 받아 오므로, 기동할 때만 읽으면
 * 새 모델의 가격이 재기동 전까지 비어 있다. 새 파일을 읽지 못하면 이전 가격을 그대로 쓴다. 파일 구조는
 * {@code { "<provider>": { "models": { "<model>": { "cost": { ... } } } } } } 이고 {@code cost} 의
 * 모든 단가는 100만 토큰당 미국 달러다.
 *
 * <p>카탈로그가 없거나 읽히지 않아도 기동에 실패하지 않는다. 가격을 모르는 것은 금액을 비워 둘 이유이지
 * 비서를 못 돌릴 이유가 아니다.
 */
@Component
@Slf4j
public class ModelsDevPriceCatalog implements PriceCatalog {

    private static final DateTimeFormatter CAPTURED_ON =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /**
     * 우리 바인딩이 쓰는 provider 이름을 카탈로그의 이름으로 옮기는 표다.
     *
     * <p>바인딩은 실행이 올라타는 credential 을 부르고 models.dev 는 모델을 내놓는 회사를 부른다.
     * {@code openai-codex} 는 Codex 구독 credential 이고 그것이 닿는 모델은 카탈로그에서 {@code openai}
     * 아래에 있다. 카탈로그에 {@code openai-codex} 항목이 없어서, 이 표가 없으면 Codex 실행은 전부
     * 가격을 찾지 못한다. 여기 없는 provider 는 그 이름 그대로 찾는다.
     */
    private static final Map<String, String> PROVIDER_ALIASES = Map.of("openai-codex", "openai");

    /** 파일의 수정 시각을 다시 보는 간격이다. 비용 환산은 실행이 끝날 때마다 불리므로 매번 디스크를 보지 않는다. */
    static final Duration CHECK_INTERVAL = Duration.ofMinutes(1);

    private final LiveProperties<PricingProperties> properties;
    private final Clock clock;
    private final ReentrantLock reloadLock = new ReentrantLock();
    /** 지금 파일을 정한 설정의 경로다. {@code current().catalogPath()} 가 이것과 다를 때만 파일과 스냅숏을 다시 정한다. */
    private volatile String appliedPath;
    /** 경로를 한 번이라도 정했는지. 설정의 경로는 null 일 수 있어 {@link #appliedPath} 만으로는 구분하지 못한다. */
    private volatile boolean pathApplied;

    private volatile Path file;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile Instant nextCheck = Instant.MIN;
    private volatile String lastFailure;
    private volatile Instant failedModifiedAt;

    @Autowired
    public ModelsDevPriceCatalog(LiveProperties<PricingProperties> properties) {
        this(properties, Clock.systemUTC());
    }

    ModelsDevPriceCatalog(LiveProperties<PricingProperties> properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        current();
    }

    @Override
    public Optional<CatalogPrice> find(String provider, String model) {
        if (provider == null || model == null) {
            return Optional.empty();
        }
        String key = provider.toLowerCase(Locale.ROOT);
        String catalogProvider = PROVIDER_ALIASES.getOrDefault(key, key);
        // 스냅숏을 한 번만 꺼내 가격과 버전을 같은 가격표에서 읽는다.
        Snapshot read = current();
        return Optional.ofNullable(read.pricesByProvider().get(catalogProvider))
                .map(models -> models.get(model.toLowerCase(Locale.ROOT)))
                .map(price -> new CatalogPrice(price, read.version()));
    }

    @Override
    public String version() {
        return current().version();
    }

    @Override
    public boolean isAvailable() {
        return current().version() != null;
    }

    private Snapshot current() {
        String catalogPath = properties.current().catalogPath();
        if (!pathApplied || !Objects.equals(catalogPath, appliedPath)) {
            apply(catalogPath);
        }
        if (file != null && !clock.instant().isBefore(nextCheck)) {
            reloadIfChanged();
        }
        return snapshot;
    }

    /**
     * 설정이 가리키는 파일로 바꾸고 들고 있던 가격을 버린다. 다음 조회가 새 파일을 곧바로 읽는다.
     *
     * <p>읽던 중인 {@link #reloadIfChanged} 가 옛 파일의 가격을 바꾼 뒤에 덮어 쓰지 않게, 읽기 lock 을 기다려 잡고 바꾼다.
     */
    private void apply(String catalogPath) {
        reloadLock.lock();
        try {
            if (pathApplied && Objects.equals(catalogPath, appliedPath)) {
                return;
            }
            file = catalogPath == null || catalogPath.isBlank() ? null : Path.of(catalogPath);
            snapshot = Snapshot.EMPTY;
            nextCheck = Instant.MIN;
            lastFailure = null;
            failedModifiedAt = null;
            appliedPath = catalogPath;
            pathApplied = true;
            if (file == null) {
                log.info("가격 카탈로그를 설정하지 않았다. 실행 비용은 비워 둔다");
            }
        } finally {
            reloadLock.unlock();
        }
    }

    /**
     * 파일의 수정 시각이 지금 들고 있는 것과 다르면 다시 읽는다.
     *
     * <p>동시에 여러 실행이 끝나도 한 번만 읽게 lock 을 잡고, 이미 누가 읽고 있으면 기다리지 않고 들고 있는
     * 가격을 쓴다. 파일이 사라졌거나 읽히지 않으면 이전 가격을 버리지 않는다. 받아 오다 실패한 파일 때문에
     * 멀쩡하던 금액이 비면 안 된다.
     */
    private void reloadIfChanged() {
        if (!reloadLock.tryLock()) {
            return;
        }
        Instant modifiedAt = null;
        Path file = this.file;
        try {
            if (file == null) {
                return;
            }
            nextCheck = clock.instant().plus(CHECK_INTERVAL);
            modifiedAt = capturedAt(file);
            if (modifiedAt.equals(snapshot.modifiedAt()) || modifiedAt.equals(failedModifiedAt)) {
                return;
            }
            String body = Files.readString(file);
            if (!modifiedAt.equals(capturedAt(file))) {
                // 읽는 사이에 파일이 바뀌었다. 새 내용에 옛 시각의 버전을 붙이지 않게 버리고 다음 확인에 맡긴다.
                nextCheck = Instant.MIN;
                return;
            }
            JsonNode root = JsonMapper.builder().build().readTree(body);
            Map<String, Map<String, ModelPrice>> parsed = readProviders(root);
            if (parsed.isEmpty()) {
                throw new IllegalStateException("no provider has a usable price");
            }
            snapshot = new Snapshot(parsed, "models.dev@" + CAPTURED_ON.format(modifiedAt), modifiedAt);
            lastFailure = null;
            failedModifiedAt = null;
            log.info("{} 에서 provider {} 개의 가격을 읽었다", file, parsed.size());
        } catch (IOException | RuntimeException ex) {
            // 내용이 틀린 파일은 바뀌기 전까지 다시 읽어도 같다. 읽기 자체가 실패한 것은 일시적일 수 있어 다음에 다시 본다.
            if (!(ex instanceof IOException)) {
                failedModifiedAt = modifiedAt;
            }
            // 1분마다 다시 보므로 같은 실패는 처음 한 번만 경고로 남긴다.
            String failure = ex.getClass().getName() + ": " + ex.getMessage();
            if (failure.equals(lastFailure)) {
                log.debug("{} 의 가격 카탈로그를 여전히 읽지 못한다", file, ex);
                return;
            }
            lastFailure = failure;
            if (snapshot.version() == null) {
                log.warn("{} 의 가격 카탈로그를 읽지 못했다. 실행 비용은 비워 둔다", file, ex);
            } else {
                log.warn("{} 의 가격 카탈로그를 다시 읽지 못했다. {} 을 계속 쓴다", file, snapshot.version(), ex);
            }
        } finally {
            reloadLock.unlock();
        }
    }

    /** 한 번 읽은 가격표다. 가격과 그 버전과 파일의 수정 시각을 함께 바꿔 조회가 섞인 값을 보지 않게 한다. */
    private record Snapshot(Map<String, Map<String, ModelPrice>> pricesByProvider, String version, Instant modifiedAt) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), null, null);
    }

    /** 카탈로그를 언제 받아 왔는지는 파일의 수정 시각이 말한다. */
    private static Instant capturedAt(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toInstant();
    }

    private static Map<String, Map<String, ModelPrice>> readProviders(JsonNode root) {
        Map<String, Map<String, ModelPrice>> byProvider = new HashMap<>();
        root.properties().forEach(provider -> {
            Map<String, ModelPrice> models = readModels(provider.getValue().path("models"));
            if (!models.isEmpty()) {
                byProvider.put(provider.getKey().toLowerCase(Locale.ROOT), models);
            }
        });
        return Map.copyOf(byProvider);
    }

    private static Map<String, ModelPrice> readModels(JsonNode models) {
        Map<String, ModelPrice> byModel = new HashMap<>();
        models.properties().forEach(model -> {
            ModelPrice price = readPrice(model.getValue().path("cost"));
            if (price != null) {
                byModel.put(model.getKey().toLowerCase(Locale.ROOT), price);
            }
        });
        return byModel;
    }

    private static ModelPrice readPrice(JsonNode cost) {
        if (!cost.isObject()) {
            return null;
        }
        ModelPrice price =
                new ModelPrice(rate(cost, "input"), rate(cost, "output"), rate(cost, "cache_read"), readTiers(cost));
        return price.isUnusable() ? null : price;
    }

    /**
     * 문맥 길이 구간을 읽는다.
     *
     * <p>{@code tiers} 는 경계값을 함께 적으므로 그쪽을 먼저 쓴다. 그보다 앞선 항목은 {@code
     * context_over_200k} 만 적어 두는데, 그 이름 자체가 경계값이다.
     */
    private static List<ModelPrice.ContextTier> readTiers(JsonNode cost) {
        List<ModelPrice.ContextTier> tiers = new ArrayList<>();
        JsonNode declared = cost.path("tiers");
        if (declared.isArray()) {
            for (JsonNode tier : declared) {
                JsonNode bound = tier.path("tier");
                if (!"context".equals(bound.path("type").asString(null))
                        || !bound.path("size").isNumber()) {
                    continue;
                }
                tiers.add(new ModelPrice.ContextTier(
                        bound.path("size").asLong(),
                        rate(tier, "input"),
                        rate(tier, "output"),
                        rate(tier, "cache_read")));
            }
        }
        JsonNode legacy = cost.path("context_over_200k");
        if (tiers.isEmpty() && legacy.isObject()) {
            tiers.add(new ModelPrice.ContextTier(
                    200_000L, rate(legacy, "input"), rate(legacy, "output"), rate(legacy, "cache_read")));
        }
        return tiers;
    }

    private static BigDecimal rate(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }
}
