package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 금융 실행 원문과 표시 값을 검증하고 사용자 데이터 key로 보호한다. 서비스와 외부 API는 모른다. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public final class ConnectorExecutionSnapshot {
    public static final String PROTOCOL = "approval-claim-v1";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(5).build())
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private static final Set<String> RESERVED_SCOPE_NAMES;

    static {
        Set<String> names = new HashSet<>(ConnectorOrderSnapshot.ORIGINAL);
        names.addAll(ConnectorOrderSnapshot.EXPECTED);
        names.addAll(
            Set.of("clientOrderId", "expected_order", "confirmHighValueOrder", "userId", "connectionId", "tool"));
        RESERVED_SCOPE_NAMES = Set.copyOf(names);
    }

    @Getter
    private final String executionArgsJson;

    @Getter
    private final String summaryJson;

    @Getter
    private final String scopeJson;

    @Getter
    private final String executionArgsSha256;

    @Getter
    private final String scopeSha256;

    private final JsonNode execution;


    /** 저장과 읽기가 함께 쓰는 실제 소비 함수다. 검증 뒤에도 원문을 다시 직렬화하지 않는다. */
    public static ConnectorExecutionSnapshot validate(
        String modelArgsJson, String executionArgsJson, String summaryJson, String scopeJson, String operation) {
        require(operation != null && Set.of("CREATE", "MODIFY", "CANCEL").contains(operation));
        JsonNode input = object(modelArgsJson, 16 * 1024);
        JsonNode args = object(executionArgsJson, 16 * 1024);
        JsonNode summary = object(summaryJson, 8 * 1024);
        JsonNode scope = object(scopeJson, 2 * 1024);
        require(!scope.isEmpty() && scope.size() <= 8);
        for (var entry : scope.properties()) {
            require(entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}"));
            require(!RESERVED_SCOPE_NAMES.contains(entry.getKey()));
            text(entry.getValue(), 1, 128);
            require(bytes(entry.getValue().stringValue()) <= 128);
            equal(entry.getValue(), args.get(entry.getKey()));
        }
        if (conditional(summary)) {
            ConnectorConditionalSnapshot.validate(input, args, summary, scope, operation);
        } else {
            ConnectorOrderSnapshot.validate(input, args, summary, scope, operation);
        }
        return new ConnectorExecutionSnapshot(
            executionArgsJson, summaryJson, scopeJson, Sha256.hex(executionArgsJson), Sha256.hex(scopeJson), args);
    }


    /** 실행 내용만 준비한다. 저장과 금융 정책 판정, 승인, 권한 발급은 호출자의 별도 경계다. */
    public static ConnectorActionExecution capture(
        ConnectorAction action,
        ConnectorBinding binding,
        String operation,
        String scopeFieldsJson,
        String executionArgsJson,
        String summaryJson,
        String scopeJson,
        TextCipher cipher,
        Instant now) {
        require(action != null && binding != null && action.id() != null && binding.id() != null);
        require(action.risk() == ToolRisk.FINANCIAL
            && action.approvalMode() == ToolApproval.ALWAYS
            && action.decision() == ActionDecision.NEEDS_APPROVAL
            && action.status() == ActionStatus.PENDING);
        require(Objects.equals(action.userId(), binding.connection().userId())
            && Objects.equals(action.agentId(), binding.agent().id())
            && Objects.equals(action.connectorId(), binding.connection().connectorId()));
        require(action.toolName() != null && !action.toolName().isBlank());
        String modelArgs = action.executableArgs().orElseThrow(ConnectorExecutionSnapshot::invalid);
        ConnectorExecutionSnapshot snapshot = validate(modelArgs, executionArgsJson, summaryJson, scopeJson, operation);
        snapshot.validateDeclaredScope(
            scopeFieldsJson, binding.connection().fields().values());
        String key = snapshot.requestKey(action.userId(), binding.connection().id(), action.toolName());
        String[] plain = {executionArgsJson, summaryJson, scopeJson};
        String[] columns = {"execution_args_json", "summary_json", "scope_json"};
        String[] stored = plain.clone();
        Long keyId = null;
        if (cipher.enabled()) {
            for (int i = 0; i < columns.length; i++) {
                SealedText sealed;
                try {
                    sealed = cipher.seal(action.userId(), aad(action, columns[i]), plain[i])
                        .orElseThrow();
                } catch (RuntimeException ignored) {
                    throw new IllegalStateException("financial snapshot encryption failed");
                }
                require(sealed.keyId() != null && sealed.content() != null);
                if (keyId != null) {
                    require(keyId.equals(sealed.keyId()));
                }
                keyId = sealed.keyId();
                stored[i] = sealed.content();
            }
        }
        return ConnectorActionExecution.stored(
            action,
            binding.connection().id(),
            binding.id(),
            binding.connection().updatedAt(),
            binding.updatedAt(),
            stored[0],
            stored[1],
            stored[2],
            keyId,
            snapshot.executionArgsSha256,
            snapshot.scopeSha256,
            key,
            PROTOCOL,
            now);
    }


    /** 저장된 key 식별자로 읽는 방식을 정하고 원문 해시와 표시 내용을 다시 검증한다. */
    public static ConnectorExecutionSnapshot open(
        ConnectorAction action, ConnectorActionExecution row, TextCipher cipher) {
        require(action != null && row != null && Objects.equals(action.id(), row.actionId()));
        require(PROTOCOL.equals(row.protocol()));
        String modelArgs = action.executableArgs().orElseThrow(ConnectorExecutionSnapshot::invalid);
        String args = openColumn(action, row.contentKeyId(), "execution_args_json", row.executionArgsJson(), cipher);
        String summary = openColumn(action, row.contentKeyId(), "summary_json", row.summaryJson(), cipher);
        String scope = openColumn(action, row.contentKeyId(), "scope_json", row.scopeJson(), cipher);
        require(Sha256.hex(args).equals(row.executionArgsSha256())
            && Sha256.hex(scope).equals(row.scopeSha256()));
        JsonNode shown = object(summary, 8 * 1024);
        text(shown.get("operation"), 1, 6);
        ConnectorExecutionSnapshot snapshot = validate(
            modelArgs, args, summary, scope, shown.get("operation").stringValue());
        require(snapshot.requestKey(action.userId(), row.connectionId(), action.toolName())
            .equals(row.requestKey()));
        return snapshot;
    }


    /** 하루 상한 합계가 쓰는 실행 원문만 푼다. 저장한 원문 해시와 다시 대조한다. */
    static JsonNode openExecutionArgs(ConnectorAction action, ConnectorActionExecution row, TextCipher cipher) {
        require(action != null && row != null && Objects.equals(action.id(), row.actionId()));
        require(PROTOCOL.equals(row.protocol()));
        String args = openColumn(action, row.contentKeyId(), "execution_args_json", row.executionArgsJson(), cipher);
        require(Sha256.hex(args).equals(row.executionArgsSha256()));
        return object(args, 16 * 1024);
    }


    /** 중복 의도 키를 한 함수에서 계산한다. decimal은 정확한 문자열로 정규화한다. */
    public String requestKey(Long userId, Long connectionId, String tool) {
        require(userId != null
            && userId > 0
            && connectionId != null
            && connectionId > 0
            && tool != null
            && !tool.isBlank());
        JsonNode summary = object(summaryJson, 8 * 1024);
        if (conditional(summary)) {
            return ConnectorConditionalSnapshot.requestKey(execution, userId, connectionId, tool, summary.get("operation").stringValue());
        }
        return ConnectorOrderSnapshot.requestKey(execution, userId, connectionId, tool);
    }


    /** 저장 scope를 현재 공개 칸과 대조하며 원문을 다시 직렬화하지 않는다. */
    public void validateDeclaredScope(String raw, Map<String, String> publicFields) {
        JsonNode scope = object(scopeJson, 2 * 1024);
        JsonNode fields = parse(raw, 2 * 1024);
        require(fields.isArray() && !fields.isEmpty() && fields.size() <= 8);
        if (conditional(object(summaryJson, 8 * 1024))) {
            require(fields.size() == 1 && "account_seq".equals(text(fields.get(0).get("arg"), 1, 64))
                && "account".equals(text(fields.get(0).get("field"), 1, 64)));
        }
        Set<String> args = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : fields) {
            keys(item, Set.of("arg", "field"));
            text(item.get("arg"), 1, 64);
            text(item.get("field"), 1, 64);
            String arg = item.get("arg").stringValue();
            String field = item.get("field").stringValue();
            require(arg.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && field.matches("[A-Za-z][A-Za-z0-9_]{0,63}"));
            require(!RESERVED_SCOPE_NAMES.contains(arg));
            require(args.add(arg) && names.add(field) && publicFields.containsKey(field));
            equal(scope.get(arg), JSON.valueToTree(publicFields.get(field)));
        }
        keys(scope, args);
    }


    private static String openColumn(
        ConnectorAction action, Long keyId, String column, String stored, TextCipher cipher) {
        if (keyId == null) {
            return stored;
        }
        try {
            return cipher.open(keyId, action.userId(), aad(action, column), stored)
                .orElseThrow();
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("financial snapshot decryption failed");
        }
    }


    private static String aad(ConnectorAction action, String column) {
        return "connector_action_execution:" + action.id() + ":column:" + column + ":user:" + action.userId();
    }


    private static boolean conditional(JsonNode summary) {
        return summary.has("orderKind") && "CONDITIONAL".equals(summary.get("orderKind").stringValue());
    }


    private static JsonNode object(String raw, int maximum) {
        JsonNode node = parse(raw, maximum);
        require(node.isObject());
        return node;
    }


    private static JsonNode parse(String raw, int maximum) {
        require(raw != null && StandardCharsets.UTF_8.newEncoder().canEncode(raw) && bytes(raw) <= maximum);
        try {
            JsonNode node = JSON.readTree(raw);
            require(node != null);
            return node;
        } catch (JacksonException ignored) {
            throw invalid();
        }
    }


    static void keys(JsonNode node, Set<String> names) {
        require(node != null && node.isObject() && node.propertyNames().equals(names));
    }


    static String text(JsonNode node, int minimum, int maximum) {
        require(node != null && node.isString());
        String value = node.stringValue();
        require(value.length() >= minimum
            && value.length() <= maximum
            && StandardCharsets.UTF_8.newEncoder().canEncode(value)
            && value.chars().noneMatch(Character::isISOControl));
        return value;
    }


    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }


    static void equal(JsonNode left, JsonNode right) {
        require(left != null && right != null && left.equals(right));
    }


    static void require(boolean condition) {
        if (!condition) {
            throw invalid();
        }
    }


    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid financial execution snapshot");
    }
}
