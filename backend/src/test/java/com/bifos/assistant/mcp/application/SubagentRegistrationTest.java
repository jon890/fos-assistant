package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 플러그인과 맞춘 하위 에이전트 session 등록 서명 계약을 고정한다(ADR-037).
 *
 * <p>기대 {@code sig} 는 Python {@code hmac} 으로 따로 계산해 {@code docs/hermes/delegation.md} 의 「하위 에이전트
 * session 등록 계약」 에 적은 값이다. 테스트 안에서 같은 방법으로 만들어 비교하면 구현과 함께 틀려도 통과하므로
 * 문자열로 둔다.
 */
class SubagentRegistrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TOKEN = "test-mcp-token-0001";
    private static final String TOKEN_HASH = "41ed73a34f34174ba0b6ded1b16cf4a085b6da45df0f711ccbeaf2a2bbc2a2ac";
    private static final String ROOT = "fos-00000000-0000-4000-8000-000000000001";
    private static final String TOP_CHILD = "하위-세션-1";
    private static final String NESTED_CHILD = "하위-세션-2";
    private static final String TOP_SIG = "ba540b481d830453accd812c8610be8d9467a532d5ff3db891514dcfe3d0726b";
    private static final String NESTED_SIG = "5479a21f26ddeb337754d4fd86dd3a0e36e0ef1f6ff2cc879c0fd07da5d84485";
    /**
     * 최상위 자식과 같은 값을 {@code _fos_ctx} 규칙으로 서명한 값이다. Python {@code hmac} 으로 따로 계산했다.
     * 서명할 글은 {@code v1\nmemory_read\n<root>\n<root>\n하위-세션-1} 이다.
     */
    private static final String FOS_CTX_RULE_SIG = "17dbb5b8aa4f08da9f262d69ab6191f9978c8bf9439f7260047f7e8444448f7e";

    @Test
    @DisplayName("토큰 해시는 표에 적은 key 와 같다")
    void tokenHashEqualsKeyInTable() {
        assertThat(AgentTokenService.hash(TOKEN)).isEqualTo(TOKEN_HASH);
    }

    @Test
    @DisplayName("최상위 자식의 test vector 가 통과하고 세 session 을 돌려준다")
    void passesTopLevelChildTestVectorAndReturnsThreeSessions() {
        SubagentRegistration registration = SubagentRegistration.verify(topChild(), TOKEN_HASH);

        assertThat(registration).isEqualTo(new SubagentRegistration(ROOT, ROOT, TOP_CHILD));
    }

    @Test
    @DisplayName("중첩 자식의 test vector 가 통과하고 세 session 을 돌려준다")
    void passesNestedChildTestVectorAndReturnsThreeSessions() {
        ObjectNode body = body(TOP_CHILD, NESTED_CHILD, NESTED_SIG);
        body.put("parent_subagent_id", "sa-1");

        SubagentRegistration registration = SubagentRegistration.verify(body, TOKEN_HASH);

        assertThat(registration).isEqualTo(new SubagentRegistration(ROOT, TOP_CHILD, NESTED_CHILD));
    }

    @Test
    @DisplayName("서명하지 않는 칸이 없거나 null 이고 모르는 키가 있어도 받는다")
    void acceptsMissingOrNullUnsignedFieldsAndUnknownKeys() {
        ObjectNode body = topChild();
        body.remove("child_subagent_id");
        body.remove("parent_subagent_id");
        body.put("added_later", 3);

        assertThat(SubagentRegistration.verify(body, TOKEN_HASH).childSessionId())
                .isEqualTo(TOP_CHILD);
    }

    @Test
    @DisplayName("sig 가 대문자면 거절한다")
    void rejectsUppercaseSig() {
        assertRejected(body -> body.put("sig", TOP_SIG.toUpperCase()));
    }

    @Test
    @DisplayName("sig 를 한 글자 바꾸면 거절한다")
    void rejectsSigWithOneCharChanged() {
        assertRejected(body -> body.put("sig", (TOP_SIG.charAt(0) == '0' ? "1" : "0") + TOP_SIG.substring(1)));
    }

    @Test
    @DisplayName("v 가 문자열이면 거절한다")
    void rejectsStringV() {
        assertRejected(body -> body.put("v", "1"));
    }

    @Test
    @DisplayName("v 가 소수면 거절한다")
    void rejectsFractionalV() {
        assertRejected(body -> body.put("v", 1.0));
    }

    @Test
    @DisplayName("서명하는 칸이 하나 없으면 거절한다")
    void rejectsWhenOneSignedFieldIsMissing() {
        assertRejected(body -> body.remove("parent_root_session_id"));
        assertRejected(body -> body.remove("parent_session_id"));
        assertRejected(body -> body.remove("child_session_id"));
        assertRejected(body -> body.remove("sig"));
    }

    @Test
    @DisplayName("서명하는 칸이 빈 문자열이면 거절한다")
    void rejectsWhenSignedFieldIsEmptyString() {
        assertRejected(body -> body.put("child_session_id", ""));
    }

    @Test
    @DisplayName("child subagent id 가 숫자면 거절한다")
    void rejectsNumericChildSubagentId() {
        assertRejected(body -> body.put("child_subagent_id", 7));
    }

    @Test
    @DisplayName("같은 값을 fos ctx 규칙으로 서명한 sig 는 거절한다")
    void rejectsSigSignedWithFosCtxRulesOverSameValue() {
        assertRejected(body -> body.put("sig", FOS_CTX_RULE_SIG));
    }

    @Test
    @DisplayName("본문이 객체가 아니거나 토큰 해시가 없으면 거절한다")
    void rejectsNonObjectBodyOrMissingTokenHash() {
        assertThatThrownBy(() -> SubagentRegistration.verify(JSON.createArrayNode(), TOKEN_HASH))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_REJECTED));
        assertThatThrownBy(() -> SubagentRegistration.verify(topChild(), ""))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_REJECTED));
    }

    private static void assertRejected(Consumer<ObjectNode> change) {
        ObjectNode body = topChild();
        change.accept(body);
        assertThatThrownBy(() -> SubagentRegistration.verify(body, TOKEN_HASH))
                .as("거절해야 하는 본문: %s", body)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_REJECTED));
    }

    private static ObjectNode topChild() {
        return body(ROOT, TOP_CHILD, TOP_SIG);
    }

    private static ObjectNode body(String parentSessionId, String childSessionId, String sig) {
        ObjectNode body = JSON.createObjectNode();
        body.put("v", 1);
        body.put("parent_session_id", parentSessionId);
        body.put("parent_root_session_id", ROOT);
        body.put("child_session_id", childSessionId);
        body.put("child_subagent_id", "sa-" + childSessionId);
        body.putNull("parent_subagent_id");
        body.put("sig", sig);
        return body;
    }
}
