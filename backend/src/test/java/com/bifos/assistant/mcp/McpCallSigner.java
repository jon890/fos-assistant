package com.bifos.assistant.mcp;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP 검사가 profile 플러그인처럼 {@code _fos_ctx} 와 하위 에이전트 등록 본문을 서명하고, 부모가 될 실행 줄을 준비하는
 * 도우미다.
 *
 * <p>서명은 운영 코드를 부르지 않고 {@code docs/hermes/delegation.md} 의 「{@code _fos_ctx} 계약」 대로 따로
 * 계산한다. key 는 토큰 원문을 SHA-256 한 소문자 16진수 문자열의 UTF-8 바이트이고, 서명할 글은
 * {@code v1\n<tool>\n<root>\n<session>\n<tool_call_id>} 다. 운영 코드로 서명하면 구현과 함께 틀려도 통과한다.
 */
public final class McpCallSigner {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant STARTED = Instant.parse("2026-09-29T00:00:00Z");

    private McpCallSigner() {}

    /** 새 뿌리 session 이다. 검사마다 새로 만들어 다른 검사가 남긴 줄과 겹치지 않게 한다. */
    static String newRoot() {
        return "fos-" + UUID.randomUUID();
    }

    /** 뿌리 session 과 같은 session 에서 부른 호출의 {@code _fos_ctx} 다. {@code tool_call_id} 는 호출마다 새 값이다. */
    public static ObjectNode context(String rawToken, String toolName, String rootSessionId) {
        return context(rawToken, toolName, rootSessionId, rootSessionId, "call_" + UUID.randomUUID());
    }

    static ObjectNode context(
            String rawToken, String toolName, String rootSessionId, String sessionId, String toolCallId) {
        ObjectNode context = JSON.createObjectNode();
        context.put("v", 1);
        context.put("root_session_id", rootSessionId);
        context.put("session_id", sessionId);
        context.put("tool_call_id", toolCallId);
        context.put("sig", sign(rawToken, String.join("\n", "v1", toolName, rootSessionId, sessionId, toolCallId)));
        return context;
    }

    /**
     * 플러그인이 {@code subagent_start} hook 에서 보내는 하위 에이전트 session 등록 본문이다.
     *
     * <p>{@code docs/hermes/delegation.md} 의 「하위 에이전트 session 등록 계약」 대로 서명할 글은
     * {@code v1-subagent\n<parent_root>\n<parent>\n<child>} 다. 서명하지 않는 두 칸은 hook 이 받은 모양대로 채운다.
     */
    static ObjectNode subagentBody(
            String rawToken, String parentRootSessionId, String parentSessionId, String childSessionId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("v", 1);
        body.put("parent_session_id", parentSessionId);
        body.put("parent_root_session_id", parentRootSessionId);
        body.put("child_session_id", childSessionId);
        body.put("child_subagent_id", "sa-" + UUID.randomUUID());
        body.putNull("parent_subagent_id");
        body.put(
                "sig",
                sign(rawToken, String.join("\n", "v1-subagent", parentRootSessionId, parentSessionId, childSessionId)));
        return body;
    }

    /**
     * 플러그인이 커넥터 도구 호출 전에 보내는 판정 요청 본문이다.
     *
     * <p>{@code docs/connectors.md} 의 「도구 호출 판정」 대로 서명할 글은
     * {@code v1-connector-policy\n<hermes_tool>\n<root>\n<session>\n<tool_call_id>\n<args_json 의 SHA-256 16진수>} 다.
     * {@code tool} 은 서명하지 않는다. null 이면 JSON null 로 싣는다.
     */
    public static ObjectNode policyBody(
            String rawToken,
            String hermesTool,
            String tool,
            String rootSessionId,
            String sessionId,
            String toolCallId,
            String argsJson) {
        ObjectNode body = JSON.createObjectNode();
        body.put("v", 1);
        body.put("root_session_id", rootSessionId);
        body.put("session_id", sessionId);
        body.put("tool_call_id", toolCallId);
        body.put("hermes_tool", hermesTool);
        if (tool == null) {
            body.putNull("tool");
        } else {
            body.put("tool", tool);
        }
        body.put("args_json", argsJson);
        body.put(
                "sig",
                sign(
                        rawToken,
                        String.join(
                                "\n",
                                "v1-connector-policy",
                                hermesTool,
                                rootSessionId,
                                sessionId,
                                toolCallId,
                                sha256(argsJson))));
        return body;
    }

    /**
     * 요청 본문이 {@code tools/call} 이고 인자에 {@code _fos_ctx} 가 없으면 서명한 값을 붙인다.
     *
     * <p>인자 검사를 보는 검사가 요청자 판정에서 먼저 막히지 않게 쓴다. 그 밖의 요청은 그대로 돌려준다.
     */
    static String withContext(String request, String rawToken, String rootSessionId) {
        ObjectNode body = (ObjectNode) JSON.readTree(request);
        if (!"tools/call".equals(body.path("method").asString())) return request;
        if (!(body.path("params") instanceof ObjectNode params)) return request;
        if (!(params.get("arguments") instanceof ObjectNode arguments) || arguments.has("_fos_ctx")) return request;
        if (!params.path("name").isString()) return request;
        arguments.set("_fos_ctx", context(rawToken, params.path("name").asString(), rootSessionId));
        return JSON.writeValueAsString(body);
    }

    /** 그 profile 로 뿌리 session 에서 도는 실행 줄을 만든다. */
    public static AgentExecution running(
            AgentExecutionRepository executions,
            Long userId,
            Long conversationId,
            String profileName,
            String rootSessionId) {
        return save(executions, userId, conversationId, profileName, rootSessionId, ExecutionStatus.RUNNING);
    }

    public static AgentExecution save(
            AgentExecutionRepository executions,
            Long userId,
            Long conversationId,
            String profileName,
            String rootSessionId,
            ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
                .userId(userId)
                .conversationId(conversationId)
                .profileName(profileName)
                .hermesSessionId(rootSessionId)
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(STARTED)
                .build());
    }

    /**
     * 그 검사가 쓰는 profile 의 하위 에이전트 session 등록 줄과 실행 줄을 지운다.
     *
     * <p>검사 클래스들이 H2 하나를 함께 쓰고, 사용자를 지워도 실행 줄은 남는다. 부모는 사용자로 거르지 않으므로
     * 남은 {@code RUNNING} 줄이 「둘 이상」 으로 걸린다. 등록 줄은 지운 실행을 origin 으로 가리키므로 먼저 지운다.
     * 검사 때문에 운영 레포지토리에 메서드를 더하지 않으려고 SQL 로 지운다.
     */
    public static void clearRuns(JdbcTemplate jdbc, List<String> profileNames) {
        for (String profileName : profileNames) {
            jdbc.update("DELETE FROM hermes_session_binding WHERE profile_name = ?", profileName);
            jdbc.update("DELETE FROM agent_execution WHERE profile_name = ?", profileName);
        }
    }

    /**
     * profile 이 빈 토큰 한 줄을 넣고 그 번호를 돌려준다.
     *
     * <p>V35 전에 남았을 수 있는 profile 없는 줄을 재현한다. 운영 코드에는 이런 줄을 만드는 길이 없다.
     */
    static long insertUnboundToken(JdbcTemplate jdbc, String rawToken, String label) {
        String tokenHash = sha256(rawToken);
        jdbc.update(
                "INSERT INTO agent_token (token_hash, label, created_at) VALUES (?, ?, ?)",
                tokenHash,
                label,
                Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT id FROM agent_token WHERE token_hash = ?", Long.class, tokenHash);
    }

    private static String sign(String rawToken, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sha256(rawToken).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
