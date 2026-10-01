package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.ConnectorPolicyRequest;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 판정 요청의 서명 계약을 고정한 값으로 확인한다. 계약은 {@code docs/connectors.md} 의 「도구 호출 판정」 이다.
 *
 * <p>{@code VECTOR_*} 는 운영 코드가 아니라 계약대로 따로 계산한 값이다. hook 쪽 검사인
 * {@code hermes/tests/test_fos_ctx.py} 의 {@code POLICY_VECTOR_*} 가 같은 값을 쓴다. 한쪽을 바꾸면 두 쪽을 함께
 * 고친다. 두 구현이 같은 벡터를 통과해야 서명이 서로 맞는다.
 */
class ConnectorPolicyRequestTest {
    /** 토큰 원문 {@code vector-token} 을 SHA-256 한 소문자 16진수다. 서명의 key 다. */
    static final String VECTOR_TOKEN_HASH = "2ce07fe9da9032a6ba2d14ea44adf290f530110a4b5d2ed67bb72384342abf8b";

    static final String VECTOR_HERMES_TOOL = "mcp__demo__write_note";
    static final String VECTOR_ROOT_SESSION_ID = "fos-root-1";
    static final String VECTOR_SESSION_ID = "fos-session-1";
    static final String VECTOR_TOOL_CALL_ID = "call_1";
    static final String VECTOR_ARGS_JSON = "{\"text\":\"안녕\"}";
    static final String VECTOR_SIG = "e23e297aec4837aeddd50e71dcde79969ad5544e6c93ea7b9ab7657f221037da";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("계약대로 계산한 서명 벡터는 통과하고 본문의 값을 그대로 돌려준다")
    void acceptsSignatureVector() {
        ConnectorPolicyRequest request = ConnectorPolicyRequest.verify(vector(), VECTOR_TOKEN_HASH);

        assertThat(request)
                .isEqualTo(new ConnectorPolicyRequest(
                        VECTOR_ROOT_SESSION_ID,
                        VECTOR_SESSION_ID,
                        VECTOR_TOOL_CALL_ID,
                        VECTOR_HERMES_TOOL,
                        "write_note",
                        VECTOR_ARGS_JSON));
    }

    @Test
    @DisplayName("tool 이 null 이어도 통과한다. tool 은 서명에 들어가지 않는다")
    void acceptsNullTool() {
        ObjectNode body = vector();
        body.putNull("tool");

        assertThat(ConnectorPolicyRequest.verify(body, VECTOR_TOKEN_HASH).tool())
                .isNull();
    }

    @Test
    @DisplayName("args_json 한 글자를 바꾸면 거절한다")
    void rejectsChangedArgs() {
        ObjectNode body = vector();
        body.put("args_json", "{\"text\":\"안냥\"}");

        assertRejected(() -> ConnectorPolicyRequest.verify(body, VECTOR_TOKEN_HASH));
    }

    @Test
    @DisplayName("서명한 다른 칸을 바꿔도 거절한다")
    void rejectsChangedSignedFields() {
        for (String key : new String[] {"hermes_tool", "root_session_id", "session_id", "tool_call_id"}) {
            ObjectNode body = vector();
            body.put(key, body.get(key).asString() + "x");

            assertRejected(() -> ConnectorPolicyRequest.verify(body, VECTOR_TOKEN_HASH));
        }
    }

    @Test
    @DisplayName("sig 를 바꾸거나 다른 토큰의 해시로 확인하면 거절한다")
    void rejectsChangedSignatureOrOtherToken() {
        ObjectNode body = vector();
        body.put("sig", "0".repeat(64));

        assertRejected(() -> ConnectorPolicyRequest.verify(body, VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(vector(), "f".repeat(64)));
    }

    @Test
    @DisplayName("args_json 이 JSON 객체가 아니면 서명이 맞아도 거절한다")
    void rejectsArgsThatAreNotJsonObject() {
        // 배열 글에 맞춰 계약대로 다시 계산한 서명이다.
        ObjectNode array = vector();
        array.put("args_json", "[1]");
        array.put("sig", "2ea450d9f9280050f9997e9f0b908388d8b88993ae68c761c78a4b827db2079f");
        ObjectNode notJson = vector();
        notJson.put("args_json", "{");

        assertRejected(() -> ConnectorPolicyRequest.verify(array, VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(notJson, VECTOR_TOKEN_HASH));
    }

    @Test
    @DisplayName("tool 이 도구 이름 형식이 아니거나 문자열이 아니면 거절한다")
    void rejectsToolThatIsNotToolName() {
        ObjectNode spaced = vector();
        spaced.put("tool", "write note");
        ObjectNode number = vector();
        number.put("tool", 1);
        ObjectNode tooLong = vector();
        tooLong.put("tool", "t".repeat(129));

        assertRejected(() -> ConnectorPolicyRequest.verify(spaced, VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(number, VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(tooLong, VECTOR_TOKEN_HASH));
    }

    @Test
    @DisplayName("v 가 정수 1 이 아니거나 칸이 빠졌거나 본문이 객체가 아니면 거절한다")
    void rejectsWrongShape() {
        ObjectNode version = vector();
        version.put("v", "1");
        ObjectNode missing = vector();
        missing.remove("tool_call_id");
        ObjectNode longId = vector();
        longId.put("session_id", "s".repeat(129));

        assertRejected(() -> ConnectorPolicyRequest.verify(version, VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(missing, VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(longId, VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(JSON.readTree("[1]"), VECTOR_TOKEN_HASH));
        assertRejected(() -> ConnectorPolicyRequest.verify(null, VECTOR_TOKEN_HASH));
    }

    private static ObjectNode vector() {
        ObjectNode body = JSON.createObjectNode();
        body.put("v", 1);
        body.put("root_session_id", VECTOR_ROOT_SESSION_ID);
        body.put("session_id", VECTOR_SESSION_ID);
        body.put("tool_call_id", VECTOR_TOOL_CALL_ID);
        body.put("hermes_tool", VECTOR_HERMES_TOOL);
        body.put("tool", "write_note");
        body.put("args_json", VECTOR_ARGS_JSON);
        body.put("sig", VECTOR_SIG);
        return body;
    }

    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTOR_POLICY_REJECTED));
    }
}
