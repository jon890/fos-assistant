package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.port.ContentDraftJobNotFound;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.util.Sha256;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.hermes.application.dto.ConnectorSnapshotDeclaration;
import com.bifos.assistant.hermes.application.dto.ContentDraftWorkerStamp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ExecutionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;

/**
 * 크기 제한 뒤 JSON을 읽고 원문·cause 없이 안전한 오류로 끝낸다.
 *
 * <p>응답 오류는 어디서 막혔는지 알리지 않으므로 실패마다 operation, 커넥터, 단계, HTTP 상태, 예외 종류만 경고로 남긴다.
 * profile 이름, 토큰, 요청과 응답 본문, 예외 메시지는 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpContentDraftSnapshotClient {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Semaphore SLOTS = new Semaphore(4);
    private static final Set<String> WORKER_ERRORS = Set.of("invalid_input", "not_found", "start_unknown", "unavailable");
    private final HermesProperties properties;
    /**
     * HTTP/1.1 로 고정한다. 기본값(HTTP/2)은 평문 주소에 h2c upgrade 를 붙이고, 대시보드의 uvicorn(httptools)은 그 POST 의 본문을
     * 버린 채 처리해 plugin 이 빈 본문을 400 으로 거절한다.
     */
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();


    public JsonNode start(String profile, String connectorId, JsonNode snapshot, ContentDraftWorkerStamp stamp, String ticket) {
        try {
            if (!snapshot.path("snapshotHash").asString().equals(stamp.snapshotHash())
                || JSON.writeValueAsBytes(snapshot).length > 262144 || ticket == null || !ticket.matches("[\\x21-\\x7e]{1,4096}")) {
                throw jobFailure("start", connectorId, "request", null, null);
            }
            var args = JSON.createObjectNode().set("snapshot", snapshot).put("jobId", stamp.jobId().toString())
                .put("ticket", ticket).set("stamp", JSON.valueToTree(stamp));
            return jobRequest(profile, connectorId, "start", args, stamp, 60);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw jobFailure("start", connectorId, "request", null, ex);
        }
    }


    /** @throws ContentDraftJobNotFound worker 가 그 stamp 의 job 을 모른다고 답했을 때. 전송 실패와 timeout 은 {@code CONTENT_DRAFT_JOB_UNAVAILABLE} 이다 */
    public JsonNode status(String profile, String connectorId, ContentDraftWorkerStamp stamp) {
        var args = JSON.createObjectNode().set("stamp", JSON.valueToTree(stamp));
        var result = jobRequest(profile, connectorId, "status", args, stamp, 5);
        if (result.has("error")) {
            if ("not_found".equals(result.path("error").stringValue())) {
                throw new ContentDraftJobNotFound();
            }
            // worker 오류 code 는 jobRequest 가 고정 어휘로만 통과시켜 남겨도 된다.
            throw jobFailure("status", connectorId, "worker-" + result.path("error").stringValue(), 200, null);
        }
        return result;
    }


    private JsonNode jobRequest(String profile, String connectorId, String operation, JsonNode args, ContentDraftWorkerStamp stamp, int seconds) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw jobFailure(operation, connectorId, "transaction", null, null);
        }
        if (!SLOTS.tryAcquire()) {
            throw jobFailure(operation, connectorId, "slots", null, null);
        }
        String stage = "request";
        Integer status = null;
        try {
            if (profile == null || "default".equals(profile) || !profile.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || connectorId == null || !connectorId.matches("[a-z0-9][a-z0-9-]{0,63}")
                || !Sha256.hex(profile).equals(stamp.profileHash())) {
                throw jobFailure(operation, connectorId, stage, null, null);
            }
            var body = JSON.createObjectNode().put("profile", profile).put("operation", operation).set("args", args);
            byte[] raw = JSON.writeValueAsBytes(body);
            if (raw.length > 524288) {
                throw jobFailure(operation, connectorId, "request-size", null, null);
            }
            var request = HttpRequest.newBuilder(URI.create(properties.dashboardBaseUrl() + "/api/connectors/" + connectorId + "/snapshot"))
                .timeout(Duration.ofSeconds(seconds)).header("Authorization", "Bearer " + properties.dashboardToken())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
            stage = "send";
            var pending = http.sendAsync(request, ignored -> new CappedSnapshotBody());
            try {
                var response = pending.get(seconds, TimeUnit.SECONDS);
                status = response.statusCode();
                if (status != 200) {
                    throw jobFailure(operation, connectorId, "status", status, null);
                }
                stage = "envelope";
                var answer = JSON.readTree(response.body());
                if (!answer.isObject() || !answer.propertyNames().equals(Set.of("ok", "result", "capabilities"))
                    || !answer.path("ok").isBoolean() || !answer.path("ok").booleanValue()) {
                    throw jobFailure(operation, connectorId, stage, status, null);
                }
                stage = "capabilities";
                var caps = answer.path("capabilities");
                ConnectorSnapshotDeclaration.requireCapabilities(caps);
                if (!caps.path(operation).booleanValue()) {
                    throw jobFailure(operation, connectorId, stage, status, null);
                }
                stage = "result";
                var result = answer.path("result");
                // worker 의 고정 오류 code 는 stamp 없이 이 모양으로만 온다. 무엇으로 읽을지는 operation 별 호출자가 정한다.
                if (result.isObject() && result.propertyNames().equals(Set.of("error")) && result.path("error").isString()
                    && WORKER_ERRORS.contains(result.path("error").stringValue())) {
                    return result;
                }
                if (!result.isObject() || JSON.writeValueAsBytes(result).length > 262144
                    || !result.path("stamp").equals(JSON.valueToTree(stamp))
                    || !result.path("job_id").isString() || !stamp.jobId().toString().equals(result.path("job_id").stringValue())) {
                    throw jobFailure(operation, connectorId, stage, status, null);
                }
                return result;
            } finally {
                pending.cancel(true);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw jobFailure(operation, connectorId, "interrupted", status, ex);
        } catch (Exception ex) {
            throw jobFailure(operation, connectorId, stage, status, ex);
        } finally {
            SLOTS.release();
        }
    }


    private static ApiException jobFailure(String operation, String connectorId, String stage, Integer status, Exception ex) {
        warn(operation, connectorId, stage, status, ex);
        return new ApiException(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE, "원고 저장 상태를 확인할 수 없습니다.");
    }


    public JsonNode compile(String profile, String connectorId, JsonNode canonicalDraft, JsonNode resolvedAssets) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw compileFailure(connectorId, "transaction", null, null);
        }
        if (!SLOTS.tryAcquire()) {
            throw compileFailure(connectorId, "slots", null, null);
        }
        String stage = "request";
        Integer status = null;
        try {
            if (profile == null || "default".equals(profile) || !profile.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || connectorId == null || !connectorId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
                throw compileFailure(connectorId, stage, null, null);
            }
            if (JSON.writeValueAsBytes(canonicalDraft).length > 65536 || !resolvedAssets.isArray() || resolvedAssets.size() > 50) {
                throw compileFailure(connectorId, "request-size", null, null);
            }
            var args = JSON.createObjectNode().set("canonicalDraft", canonicalDraft).set("resolvedAssets", resolvedAssets);
            var body = JSON.createObjectNode().put("profile", profile).put("operation", "compile").set("args", args);
            byte[] raw = JSON.writeValueAsBytes(body);
            if (raw.length > 524288) {
                throw compileFailure(connectorId, "request-size", null, null);
            }
            var request = HttpRequest.newBuilder(URI.create(properties.dashboardBaseUrl() + "/api/connectors/" + connectorId + "/snapshot"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + properties.dashboardToken())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
            stage = "send";
            var pending = http.sendAsync(request, ignored -> new CappedSnapshotBody());
            try {
                var response = pending.get(5, TimeUnit.SECONDS);
                status = response.statusCode();
                if (status != 200) {
                    throw compileFailure(connectorId, "status", status, null);
                }
                stage = "envelope";
                var answer = JSON.readTree(response.body());
                if (!answer.isObject() || !answer.propertyNames().equals(Set.of("ok", "result", "capabilities"))
                    || !answer.path("ok").isBoolean() || !answer.path("ok").booleanValue()
                    || JSON.writeValueAsBytes(answer.path("result")).length > 262144) {
                    throw compileFailure(connectorId, stage, status, null);
                }
                stage = "capabilities";
                ConnectorSnapshotDeclaration.requireCapabilities(answer.path("capabilities"));
                stage = "result";
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
                    throw compileFailure(connectorId, stage, status, null);
                }
                return answer.get("result");
            } finally {
                pending.cancel(true);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw compileFailure(connectorId, "interrupted", status, ex);
        } catch (Exception ex) {
            throw compileFailure(connectorId, stage, status, ex);
        } finally {
            SLOTS.release();
        }
    }


    private static ApiException compileFailure(String connectorId, String stage, Integer status, Exception ex) {
        warn("compile", connectorId, stage, status, ex);
        return new ApiException(ErrorCode.CONTENT_DRAFT_UNAVAILABLE, "원고를 사용할 수 없습니다.");
    }


    /** 전송 실패는 {@link ExecutionException} 에 싸여 오므로 안쪽 예외의 종류를 남긴다. 형식이 틀린 커넥터 번호는 그대로 남기지 않는다. */
    private static void warn(String operation, String connectorId, String stage, Integer status, Exception ex) {
        Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
        log.warn("원고 snapshot {} 실패 connector={} stage={} status={} error={}", operation,
            connectorId != null && connectorId.matches("[a-z0-9][a-z0-9-]{0,63}") ? connectorId : "invalid", stage,
            status == null ? "-" : status, cause == null ? "-" : cause.getClass().getSimpleName());
    }


    private static boolean hash(JsonNode value) {
        return value.isString() && value.stringValue().matches("[0-9a-f]{64}");
    }
}
