package com.bifos.assistant.mcp.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.chat.application.MediaObservationInputReader;
import com.bifos.assistant.chat.application.MediaObservationPages;
import com.bifos.assistant.chat.application.MediaObservationService;
import com.bifos.assistant.chat.application.model.MediaObservationView;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.ExternalData;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 서명된 현재 대화의 모델 선언을 저장한다. 실행 출처와 실제 모델의 검증 여부는 구분한다. */
@Service
public class McpMediaObservationTools {
    public static final String LIST = "list_media_observations";
    public static final String RECORD = "record_media_observation";
    private static final String INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";
    private final MediaObservationPages pages;
    private final MediaObservationService observations;
    private final MediaObservationInputReader reader;
    private final UserAccessPolicy users;
    private final AgentService agents;
    private final SessionOwnerResolver sessions;
    private final ObjectMapper json;
    private final TransactionTemplate permissions;

    public McpMediaObservationTools(
            MediaObservationPages pages,
            MediaObservationService observations,
            MediaObservationInputReader reader,
            UserAccessPolicy users,
            AgentService agents,
            SessionOwnerResolver sessions,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.pages = pages;
        this.observations = observations;
        this.reader = reader;
        this.users = users;
        this.agents = agents;
        this.sessions = sessions;
        this.json = json;
        this.permissions = new TransactionTemplate(manager);
        permissions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        permissions.setReadOnly(true);
    }

    public Map<String, Object> list(McpCaller caller, String afterAssetId, int limit) {
        return guarded(caller, () -> {
            var page = pages.list(caller.user(), caller.originExecution().conversationId(), afterAssetId, limit);
            ObjectNode result = json.createObjectNode();
            var items = result.putArray("items");
            page.items().forEach(view -> items.add(response(view)));
            result.put("nextAfterAssetId", page.nextAfterAssetId());
            return result;
        });
    }

    public Map<String, Object> record(
            McpCaller caller, String assetId, long expectedRevision, UUID requestId, JsonNode body) {
        return guarded(caller, () -> {
            var source = new ObservationProvenance(
                    ObservationProvenanceKind.MODEL_RESULT,
                    caller.executionId(),
                    "UNKNOWN",
                    null,
                    "UNKNOWN",
                    null,
                    ObservationProvenance.SCHEMA_VERSION,
                    ObservationProvenance.PROMPT_VERSION,
                    null);
            var input = reader.read(body, source);
            return response(observations.record(
                    caller.user(),
                    caller.originExecution().conversationId(),
                    MediaObservationInputReader.assetId(assetId),
                    expectedRevision,
                    requestId,
                    input,
                    source));
        });
    }

    /** 권한 조회는 짧은 별도 트랜잭션이다. 저장은 기존 서비스의 독립 commit 경계를 유지한다. */
    private Map<String, Object> guarded(McpCaller caller, Supplier<JsonNode> action) {
        try {
            requireCurrent(caller);
            JsonNode result = action.get();
            requireCurrent(caller);
            return McpToolService.result(ExternalData.wrap(json.writeValueAsString(result)), false);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.VALIDATION_FAILED) {
                throw MediaObservationInputReader.invalid();
            }
            if (ex.code() == ErrorCode.MCP_CALL_CONTEXT_INVALID) {
                return McpToolService.result(INVALID_CONTEXT, true);
            }
            if (ex.code() == ErrorCode.MEDIA_OBSERVATION_CONFLICT
                    && ex.getMessage() != null
                    && ex.getMessage().matches("revision=[0-9]+")) {
                try {
                    return failure(ex.code(), Long.parseLong(ex.getMessage().substring("revision=".length())));
                } catch (NumberFormatException ignored) {
                    return failure(ex.code(), null);
                }
            }
            return failure(ex.code(), null);
        } catch (RuntimeException ex) {
            return failure(ErrorCode.INTERNAL_ERROR, null);
        }
    }

    private void requireCurrent(McpCaller caller) {
        permissions.executeWithoutResult(status -> {
            var original = caller.originExecution();
            var context = caller.context();
            var origin = sessions.resolve(original.profileName(), context.rootSessionId(), context.sessionId());
            if (!original.id().equals(origin.id())
                    || !caller.user().id().equals(origin.userId())
                    || origin.conversationId() == null
                    || !origin.conversationId().equals(original.conversationId())
                    || !original.profileName().equals(origin.profileName())
                    || !users.allowed(origin.userId())) {
                throw invalidContext();
            }
            var agent = agents.findById(origin.agentId()).orElseThrow(McpMediaObservationTools::invalidContext);
            if (agent.isDeleted()
                    || !agent.enabled()
                    || agent.connectorManaged()
                    || !agent.isReadableBy(origin.userId())
                    || !origin.profileName().equals(agent.hermesProfile())) {
                throw invalidContext();
            }
        });
    }

    private ObjectNode response(MediaObservationView view) {
        ObjectNode result = json.valueToTree(view);
        String assurance = view.observation() == null || view.provenance() == null
                ? null
                : view.provenance().kind() == ObservationProvenanceKind.USER_CORRECTION
                        ? "USER_CORRECTION"
                        : "MODEL_UNVERIFIED";
        result.put("sourceAssurance", assurance);
        return result;
    }

    private Map<String, Object> failure(ErrorCode code, Long revision) {
        ObjectNode result = json.createObjectNode().put("code", code.name());
        if (revision != null) {
            result.put("currentRevision", revision);
        }
        return McpToolService.result(json.writeValueAsString(result), true);
    }

    private static ApiException invalidContext() {
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }

    public Map<String, Object> listDefinition() {
        return definition(
                LIST,
                "현재 대화의 사진 관찰을 읽는다. 본문·coverage는 제출자의 선언이며 모델 신원·판독 품질은 미검증이다. "
                        + "부족한 범위는 attachment_inspect로 원본을 확인한다. 사용자 정정을 우선하고 권한 오류는 새 대화에서 재시도한다.",
                Map.of(
                        "afterAssetId",
                        Map.of("type", List.of("string", "null"), "pattern", "^[1-9][0-9]*$"),
                        "limit",
                        Map.of("type", List.of("integer", "null"), "minimum", 1, "maximum", 30)),
                List.of());
    }

    public Map<String, Object> recordDefinition() {
        return definition(
                RECORD,
                "현재 대화에서 직접 확인한 사진의 모델 관찰만 제출한다. 확인 범위를 coverage에 적는다. "
                        + "provider/model은 UNKNOWN으로 보존되고 MODEL_UNVERIFIED다. 사용자 정정을 바꾸지 못한다. "
                        + "충돌은 currentRevision을 다시 읽고 판단한다. 권한 오류는 새 대화에서 재시도한다.",
                Map.of(
                        "assetId",
                        Map.of("type", "string", "pattern", "^[1-9][0-9]*$"),
                        "expectedRevision",
                        Map.of("type", "integer", "minimum", 0),
                        "requestId",
                        Map.of("type", "string", "format", "uuid"),
                        "observation",
                        observationSchema()),
                List.of("assetId", "expectedRevision", "requestId", "observation"));
    }

    private static Map<String, Object> observationSchema() {
        var region = object(
                Map.of(
                        "x",
                        Map.of("type", "number", "minimum", 0, "maximum", 1),
                        "y",
                        Map.of("type", "number", "minimum", 0, "maximum", 1),
                        "width",
                        Map.of("type", "number", "exclusiveMinimum", 0, "maximum", 1),
                        "height",
                        Map.of("type", "number", "exclusiveMinimum", 0, "maximum", 1)),
                List.of("x", "y", "width", "height"));
        var coverage = object(
                Map.of(
                        "mode",
                        Map.of("type", "string", "enum", List.of("ORIGINAL", "OVERVIEW", "CROP", "FIRST_FRAME")),
                        "region",
                        Map.of("anyOf", List.of(region, Map.of("type", "null"))),
                        "frame",
                        Map.of("type", List.of("integer", "null"), "enum", Arrays.asList(0, null))),
                List.of("mode"));
        var claim = object(
                Map.of(
                        "kind",
                        Map.of("type", "string", "enum", List.of("VISUAL", "OCR")),
                        "text",
                        Map.of("type", "string", "minLength", 1, "maxLength", 500),
                        "confidence",
                        Map.of("type", "string", "enum", List.of("CONFIRMED", "UNCERTAIN")),
                        "evidence",
                        Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "maxItems",
                                32768,
                                "items",
                                Map.of("type", "string", "minLength", 1, "maxLength", 500))),
                List.of("kind", "text", "confidence", "evidence"));
        return object(
                Map.of(
                        "status",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of("PROCESSING", "SUCCEEDED", "PARTIAL", "FAILED", "NEEDS_REVIEW")),
                        "summary",
                        Map.of("type", List.of("string", "null"), "maxLength", 2000),
                        "claims",
                        Map.of("type", List.of("array", "null"), "maxItems", 50, "items", claim),
                        "uncertainties",
                        Map.of(
                                "type",
                                List.of("array", "null"),
                                "maxItems",
                                30,
                                "items",
                                Map.of("type", "string", "minLength", 1, "maxLength", 500)),
                        "coverage",
                        Map.of("anyOf", List.of(coverage, Map.of("type", "null"))),
                        "errorCode",
                        Map.of("type", List.of("string", "null"), "pattern", "^[A-Z][A-Z0-9_]{0,63}$")),
                List.of("status"));
    }

    private static Map<String, Object> definition(
            String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("name", name, "description", description, "inputSchema", object(properties, required));
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", required);
    }
}
