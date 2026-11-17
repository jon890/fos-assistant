package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.mcp.application.dto.SubagentStop;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 플러그인과 맞춘 하위 에이전트 끝 소식 서명 계약을 고정한다(ADR-20261117 / native-delegation-wake).
 *
 * <p>기대 {@code sig} 는 Python {@code hmac} 으로 따로 계산해 {@code hermes/plugins/fos-ctx/README.md} 의 「하위 에이전트 끝 소식
 * 계약」 에 적은 값이다.
 */
class SubagentStopTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TOKEN_HASH = "41ed73a34f34174ba0b6ded1b16cf4a085b6da45df0f711ccbeaf2a2bbc2a2ac";
    private static final String ROOT = "fos-00000000-0000-4000-8000-000000000001";
    private static final String CHILD = "하위-세션-1";
    private static final String STOP_SIG = "d59b3af5d4d924b380684c14de9f3736bfb5f5a04ed18982404e2a3f2568ebd0";
    /** 같은 세 session 의 등록 서명이다. 끝 소식에 쓰지 못해야 한다. */
    private static final String REGISTRATION_SIG = "ba540b481d830453accd812c8610be8d9467a532d5ff3db891514dcfe3d0726b";


    @Test
    @DisplayName("끝 소식 test vector 가 통과하고 상태와 결과 글을 돌려준다")
    void passesTestVectorAndReturnsStatusAndSummary() {
        SubagentStop stop = SubagentStop.verify(body(STOP_SIG), TOKEN_HASH);

        assertThat(stop).isEqualTo(new SubagentStop(ROOT, ROOT, CHILD, "completed", "목록을 정리했다"));
    }


    @Test
    @DisplayName("같은 세 session 의 등록 서명은 끝 소식으로 받지 않는다")
    void rejectsRegistrationSignature() {
        assertRejected(body(REGISTRATION_SIG));
    }


    @Test
    @DisplayName("상태가 글이 아니면 거절하고 결과 글이 없으면 null 이다")
    void rejectsNonTextStatusAndAcceptsMissingSummary() {
        ObjectNode numberStatus = body(STOP_SIG);
        numberStatus.put("child_status", 3);
        ObjectNode noSummary = body(STOP_SIG);
        noSummary.remove("child_summary");

        assertRejected(numberStatus);
        assertThat(SubagentStop.verify(noSummary, TOKEN_HASH).childSummary()).isNull();
    }


    @Test
    @DisplayName("toString 은 결과 글을 내지 않는다")
    void toStringHidesSummary() {
        assertThat(SubagentStop.verify(body(STOP_SIG), TOKEN_HASH).toString()).doesNotContain("목록을 정리했다");
    }


    private static void assertRejected(ObjectNode body) {
        assertThatThrownBy(() -> SubagentStop.verify(body, TOKEN_HASH))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_BINDING_REJECTED));
    }


    private static ObjectNode body(String sig) {
        ObjectNode body = JSON.createObjectNode();
        body.put("v", 1);
        body.put("parent_session_id", ROOT);
        body.put("parent_root_session_id", ROOT);
        body.put("child_session_id", CHILD);
        body.put("child_status", "completed");
        body.put("child_summary", "목록을 정리했다");
        body.put("sig", sig);
        return body;
    }
}
