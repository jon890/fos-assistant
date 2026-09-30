package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("토큰 해시는 표에 적은 key 와 같다")
    void tokenHashEqualsKeyInTable() {
        assertThat(AgentTokenService.hash(TOKEN)).isEqualTo(TOKEN_HASH);
    }

    @Test
    @DisplayName("test vector 가 통과하고 세 칸을 그대로 돌려준다")
    void passesTestVectorAndReturnsThreeFieldsAsIs() {
        McpCallContext context = McpCallContext.verify("agent_delegate", ctx(DELEGATE_SIG), TOKEN_HASH);

        assertThat(context.rootSessionId()).isEqualTo(ROOT_SESSION_ID);
        assertThat(context.sessionId()).isEqualTo(SESSION_ID);
        assertThat(context.toolCallId()).isEqualTo(TOOL_CALL_ID);
    }

    @Test
    @DisplayName("도구 이름이 서명에 들어가 다른 도구의 서명은 통과하지 못한다")
    void toolNameIsInSignatureSoOtherToolsSignatureFails() {
        assertThat(McpCallContext.verify("agent_status", ctx(STATUS_SIG), TOKEN_HASH).toolCallId())
                .isEqualTo(TOOL_CALL_ID);
        assertRejected("agent_status", ctx(DELEGATE_SIG));
    }

    @Test
    @DisplayName("한 글자 바꾼 sig 와 대문자 sig 는 거절한다")
    void rejectsSigWithOneCharChangedAndUppercaseSig() {
        String changed = DELEGATE_SIG.substring(0, 63) + (DELEGATE_SIG.endsWith("b") ? "c" : "b");

        assertRejected("agent_delegate", ctx(changed));
        assertRejected("agent_delegate", ctx(DELEGATE_SIG.toUpperCase()));
    }

    @Test
    @DisplayName("다른 토큰의 해시로는 통과하지 못한다")
    void doesNotPassWithHashOfOtherToken() {
        assertRejected("agent_delegate", ctx(DELEGATE_SIG), AgentTokenService.hash("test-mcp-token-0002"));
    }

    @Test
    @DisplayName(" fos ctx 가 없거나 객체가 아니면 거절한다")
    void rejectsWhenFosCtxIsMissingOrNotObject() {
        assertRejected("agent_delegate", null);
        assertRejected("agent_delegate", JSON.getNodeFactory().stringNode("{}"));
    }

    @Test
    @DisplayName("키 하나가 빠지거나 비었거나 문자열이 아니면 거절한다")
    void rejectsWhenOneKeyIsMissingBlankOrNotString() {
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
    @DisplayName("v 는 정수 1 만 받는다")
    void acceptsOnlyIntegerOneForV() {
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
    @DisplayName("JSON 본문에서 읽은 정수 1 은 받고 1 0 과 지수 표기는 거절한다")
    void acceptsIntegerOneReadFromJsonBodyAndRejectsOneDotZeroAndExponent() {
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
