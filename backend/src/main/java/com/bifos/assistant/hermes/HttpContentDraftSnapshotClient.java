package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.hermes.dto.ConnectorSnapshotDeclaration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;

/** 크기 제한 뒤 JSON을 읽고 원문·cause 없이 안전한 오류로 끝낸다. */
@Component
@RequiredArgsConstructor
public class HttpContentDraftSnapshotClient {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Semaphore SLOTS = new Semaphore(4);
    private final HermesProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public JsonNode compile(String profile, String connectorId, JsonNode canonicalDraft, JsonNode resolvedAssets) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || !SLOTS.tryAcquire()) {
            throw unavailable();
        }
        try {
            if (profile == null || "default".equals(profile) || !profile.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || connectorId == null || !connectorId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
                throw unavailable();
            }
            if (JSON.writeValueAsBytes(canonicalDraft).length > 65536 || !resolvedAssets.isArray() || resolvedAssets.size() > 50) {
                throw unavailable();
            }
            var args = JSON.createObjectNode().set("canonicalDraft", canonicalDraft).set("resolvedAssets", resolvedAssets);
            var body = JSON.createObjectNode().put("profile", profile).put("operation", "compile").set("args", args);
            byte[] raw = JSON.writeValueAsBytes(body);
            if (raw.length > 524288) {
                throw unavailable();
            }
            var request = HttpRequest.newBuilder(URI.create(properties.dashboardBaseUrl() + "/api/connectors/" + connectorId + "/snapshot"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + properties.dashboardToken())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
            var pending = http.sendAsync(request, ignored -> new CappedSnapshotBody());
            try {
                var response = pending.get(5, TimeUnit.SECONDS);
                if (response.statusCode() != 200) {
                    throw unavailable();
                }
                var answer = JSON.readTree(response.body());
                if (!answer.isObject() || !answer.propertyNames().equals(Set.of("ok", "result", "capabilities"))
                    || !answer.path("ok").isBoolean() || !answer.path("ok").booleanValue()
                    || JSON.writeValueAsBytes(answer.path("result")).length > 262144) {
                    throw unavailable();
                }
                ConnectorSnapshotDeclaration.requireCompileOnly(answer.path("capabilities"));
                var result = answer.path("result");
                if (!result.isObject() || !result.propertyNames().equals(Set.of("schemaVersion", "compilerVersion", "draftId", "revision",
                    "canonicalBlocks", "payload", "photoNotes", "assetManifest", "payloadFingerprint", "snapshotHash"))
                    || !result.path("schemaVersion").isInt() || result.path("schemaVersion").intValue() != 1
                    || !"content-compiler-v1".equals(result.path("compilerVersion").stringValue())
                    || !canonicalDraft.path("draftId").equals(result.path("draftId"))
                    || !result.path("revision").isIntegralNumber() || !result.path("revision").canConvertToLong()
                    || canonicalDraft.path("revision").longValue() != result.path("revision").longValue()
                    || !result.path("canonicalBlocks").isArray() || !result.path("assetManifest").isArray()
                    || result.path("assetManifest").size() > 50 || !result.path("photoNotes").isObject()
                    || !result.path("payload").isObject() || !hash(result.path("payloadFingerprint")) || !hash(result.path("snapshotHash"))) {
                    throw unavailable();
                }
                return answer.get("result");
            } finally {
                pending.cancel(true);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ex) {
            throw unavailable();
        } finally {
            SLOTS.release();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.CONTENT_DRAFT_UNAVAILABLE, "원고를 사용할 수 없습니다.");
    }

    private static boolean hash(JsonNode value) {
        return value.isString() && value.stringValue().matches("[0-9a-f]{64}");
    }
}
