package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 플러그인과 맞춘 서명 계약을 고정한다.
 *
 * <p>기대 {@code sig} 는 Python {@code hmac} 으로 따로 계산해 {@code docs/hermes/delegation.md} 의
 * 「{@code _fos_ctx} 계약」 에 적은 값이다. 테스트 안에서 같은 방법으로 만들어 비교하면 구현과 함께 틀려도
 * 통과하므로 문자열로 둔다.
 */
class McpCallContextTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TOKEN = "test-mcp-token-0001";
    private static final String TOKEN_HASH = "41ed73a34f34174ba0b6ded1b16cf4a085b6da45df0f711ccbeaf2a2bbc2a2ac";
    private static final String ROOT_SESSION_ID = "fos-00000000-0000-4000-8000-000000000001";
    private static final String SESSION_ID = "하위-세션-1";
    private static final String TOOL_CALL_ID = "call_0001";
    private static final String DELEGATE_SIG = "b28a128dbb642aba7a8b4c35dcb237e2feb5a452305ec275909c32a00ae1b25b";
    private static final String STATUS_SIG = "62109c6c99e7ed4638e4343f1e5b6b22a3f55dd974560916149986866c253236";

    @Test
    void 토큰_해시는_표에_적은_key_와_같다() {
        assertThat(AgentTokenService.hash(TOKEN)).isEqualTo(TOKEN_HASH);
    }

    @Test
    void test_vector_가_통과하고_세_칸을_그대로_돌려준다() {
        McpCallContext context = McpCallContext.verify("agent_delegate", ctx(DELEGATE_SIG), TOKEN_HASH);

        assertThat(context.rootSessionId()).isEqualTo(ROOT_SESSION_ID);
        assertThat(context.sessionId()).isEqualTo(SESSION_ID);
        assertThat(context.toolCallId()).isEqualTo(TOOL_CALL_ID);
    }

    @Test
    void 도구_이름이_서명에_들어가_다른_도구의_서명은_통과하지_못한다() {
        assertThat(McpCallContext.verify("agent_status", ctx(STATUS_SIG), TOKEN_HASH).toolCallId())
                .isEqualTo(TOOL_CALL_ID);
        assertRejected("agent_status", ctx(DELEGATE_SIG));
    }

    @Test
    void 한_글자_바꾼_sig_와_대문자_sig_는_거절한다() {
        String changed = DELEGATE_SIG.substring(0, 63) + (DELEGATE_SIG.endsWith("b") ? "c" : "b");

        assertRejected("agent_delegate", ctx(changed));
        assertRejected("agent_delegate", ctx(DELEGATE_SIG.toUpperCase()));
    }

    @Test
    void 다른_토큰의_해시로는_통과하지_못한다() {
        assertRejected("agent_delegate", ctx(DELEGATE_SIG), AgentTokenService.hash("test-mcp-token-0002"));
    }

    @Test
    void _fos_ctx_가_없거나_객체가_아니면_거절한다() {
        assertRejected("agent_delegate", null);
        assertRejected("agent_delegate", JSON.getNodeFactory().stringNode("{}"));
    }

    @Test
    void 키_하나가_빠지거나_비었거나_문자열이_아니면_거절한다() {
        for (String key : new String[] {"v", "session_id", "root_session_id", "tool_call_id", "sig"}) {
            ObjectNode missing = ctx(DELEGATE_SIG);
            missing.remove(key);
            assertRejected("agent_delegate", missing);
        }
        ObjectNode empty = ctx(DELEGATE_SIG);
        empty.put("session_id", "");
        assertRejected("agent_delegate", empty);
        ObjectNode number = ctx(DELEGATE_SIG);
        number.put("tool_call_id", 1);
        assertRejected("agent_delegate", number);
    }

    @Test
    void v_는_정수_1_만_받는다() {
        ObjectNode two = ctx(DELEGATE_SIG);
        two.put("v", 2);
        ObjectNode text = ctx(DELEGATE_SIG);
        text.put("v", "1");
        ObjectNode decimal = ctx(DELEGATE_SIG);
        decimal.put("v", 1.0);

        assertRejected("agent_delegate", two);
        assertRejected("agent_delegate", text);
        assertRejected("agent_delegate", decimal);
    }

    @Test
    void JSON_본문에서_읽은_정수_1_은_받고_1_0_과_지수_표기는_거절한다() {
        String body = "{\"v\":%s,\"session_id\":\"" + SESSION_ID + "\",\"root_session_id\":\"" + ROOT_SESSION_ID
                + "\",\"tool_call_id\":\"" + TOOL_CALL_ID + "\",\"sig\":\"" + DELEGATE_SIG + "\"}";

        assertThat(McpCallContext.verify("agent_delegate", JSON.readTree(body.formatted("1")), TOKEN_HASH).rootSessionId())
                .isEqualTo(ROOT_SESSION_ID);
        assertRejected("agent_delegate", JSON.readTree(body.formatted("1.0")));
        assertRejected("agent_delegate", JSON.readTree(body.formatted("1e0")));
    }

    private static ObjectNode ctx(String sig) {
        ObjectNode node = JSON.createObjectNode();
        node.put("v", 1);
        node.put("session_id", SESSION_ID);
        node.put("root_session_id", ROOT_SESSION_ID);
        node.put("tool_call_id", TOOL_CALL_ID);
        node.put("sig", sig);
        return node;
    }

    private static void assertRejected(String toolName, JsonNode fosCtx) {
        assertRejected(toolName, fosCtx, TOKEN_HASH);
    }

    private static void assertRejected(String toolName, JsonNode fosCtx, String tokenHash) {
        assertThatThrownBy(() -> McpCallContext.verify(toolName, fosCtx, tokenHash))
                .as("tool=%s ctx=%s", toolName, fosCtx)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MCP_CALL_CONTEXT_INVALID));
    }
}
