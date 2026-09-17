package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.ModelPrice;
import com.bifos.assistant.usage.domain.PriceCatalog;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hermes 가 홈서버에 이미 들고 있는 models.dev 카탈로그에서 읽은 가격이다.
 *
 * <p>기동할 때 한 번 읽어 메모리에 둔다. 그래서 조회는 디스크를 건드리지 않는다. 파일 구조는
 * {@code { "<provider>": { "models": { "<model>": { "cost": { ... } } } } } } 이고 {@code cost} 의
 * 모든 단가는 100만 토큰당 미국 달러다.
 *
 * <p>카탈로그가 없거나 읽히지 않아도 기동에 실패하지 않는다. 가격을 모르는 것은 금액을 비워 둘 이유이지
 * 비서를 못 돌릴 이유가 아니다.
 */
@Component
public class ModelsDevPriceCatalog implements PriceCatalog {

    private static final Logger log = LoggerFactory.getLogger(ModelsDevPriceCatalog.class);

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

    private final Map<String, Map<String, ModelPrice>> pricesByProvider;
    private final String version;

    public ModelsDevPriceCatalog(PricingProperties properties) {
        String catalogPath = properties.catalogPath();
        Map<String, Map<String, ModelPrice>> parsed = Map.of();
        String parsedVersion = null;
        if (catalogPath == null || catalogPath.isBlank()) {
            log.info("가격 카탈로그를 설정하지 않았다. 실행 비용은 비워 둔다");
        } else {
            Path file = Path.of(catalogPath);
            try {
                JsonNode root = JsonMapper.builder().build().readTree(Files.readString(file));
                parsed = readProviders(root);
                parsedVersion = "models.dev@" + CAPTURED_ON.format(capturedAt(file));
                log.info("{} 에서 provider {} 개의 가격을 읽었다", file, parsed.size());
            } catch (IOException | RuntimeException ex) {
                log.warn("{} 의 가격 카탈로그를 읽지 못했다. 실행 비용은 비워 둔다", file, ex);
            }
        }
        this.pricesByProvider = parsed;
        this.version = parsedVersion;
    }

    @Override
    public Optional<ModelPrice> find(String provider, String model) {
        if (provider == null || model == null) {
            return Optional.empty();
        }
        String key = provider.toLowerCase(Locale.ROOT);
        String catalogProvider = PROVIDER_ALIASES.getOrDefault(key, key);
        return Optional.ofNullable(pricesByProvider.get(catalogProvider))
                .map(models -> models.get(model.toLowerCase(Locale.ROOT)));
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public boolean isAvailable() {
        return version != null;
    }

    /** 카탈로그를 언제 받아 왔는지는 파일의 수정 시각이 말한다. */
    private static Instant capturedAt(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toInstant();
    }

    private static Map<String, Map<String, ModelPrice>> readProviders(JsonNode root) {
        Map<String, Map<String, ModelPrice>> byProvider = new HashMap<>();
        root.properties()
                .forEach(
                        provider -> {
                            Map<String, ModelPrice> models = readModels(provider.getValue().path("models"));
                            if (!models.isEmpty()) {
                                byProvider.put(provider.getKey().toLowerCase(Locale.ROOT), models);
                            }
                        });
        return Map.copyOf(byProvider);
    }

    private static Map<String, ModelPrice> readModels(JsonNode models) {
        Map<String, ModelPrice> byModel = new HashMap<>();
        models.properties()
                .forEach(
                        model -> {
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
                new ModelPrice(
                        rate(cost, "input"),
                        rate(cost, "output"),
                        rate(cost, "cache_read"),
                        readTiers(cost));
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
                if (!"context".equals(bound.path("type").asString(null)) || !bound.path("size").isNumber()) {
                    continue;
                }
                tiers.add(
                        new ModelPrice.ContextTier(
                                bound.path("size").asLong(),
                                rate(tier, "input"),
                                rate(tier, "output"),
                                rate(tier, "cache_read")));
            }
        }
        JsonNode legacy = cost.path("context_over_200k");
        if (tiers.isEmpty() && legacy.isObject()) {
            tiers.add(
                    new ModelPrice.ContextTier(
                            200_000L, rate(legacy, "input"), rate(legacy, "output"), rate(legacy, "cache_read")));
        }
        return tiers;
    }

    private static BigDecimal rate(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }
}
