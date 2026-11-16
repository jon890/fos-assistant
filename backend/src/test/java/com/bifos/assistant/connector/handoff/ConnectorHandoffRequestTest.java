package com.bifos.assistant.connector.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.handoff.application.dto.ConnectorHandoffRequest;
import com.bifos.assistant.shared.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 화면 넘기기 알림 본문의 모양과 서명 확인이다. 확인 값은 {@code hermes/tests/test_fos_ctx.py} 의 {@code HANDOFF_VECTOR_SIG} 와 같다. */
class ConnectorHandoffRequestTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "2ce07fe9da9032a6ba2d14ea44adf290f530110a4b5d2ed67bb72384342abf8b";
    private static final String SIG = "b335ba30659f0bc2396026c66f20ee5643e6fd7989e3a0fa8c7c601498befee3";
    /** 탭 없는 {@code human_browser} 의 확인 값이다. {@code hermes/tests/test_fos_ctx.py} 의 {@code HANDOFF_TABLESS_SIG} 와 같다. */
    private static final String TABLESS_SIG = "19ead4bb3f5a9ae30dd75e7abf896c9fed9db0f4ee6939f58ce5d3a4ff7105a1";


    @Test
    @DisplayName("plugin 과 같은 확인 값의 서명을 받아 칸을 돌려준다")
    void acceptsVector() {
        ConnectorHandoffRequest request = ConnectorHandoffRequest.verify(body(), KEY);

        assertThat(request.hermesTool()).isEqualTo("mcp__demo__write_note");
        assertThat(request.rootSessionId()).isEqualTo("fos-root-1");
        assertThat(request.code()).isEqualTo("DEMO_LOGIN_REQUIRED");
        assertThat(request.need()).isEqualTo(HandoffNeed.LOGIN);
        assertThat(request.tab()).isEqualTo("TAB1");
    }


    @Test
    @DisplayName("플러그인이 낼 수 있는 낱말만 받고 결제와 탭 확인은 받지 않으며, 모든 단계에 카드 제목이 있다")
    void acceptsEveryNeed() {
        assertThat(HandoffNeed.fromWire("bot_check")).contains(HandoffNeed.BOT_CHECK);
        assertThat(HandoffNeed.fromWire("unexpected_page")).contains(HandoffNeed.UNEXPECTED_PAGE);
        assertThat(HandoffNeed.fromWire("human_browser")).contains(HandoffNeed.HUMAN_BROWSER);
        assertThat(HandoffNeed.fromWire("BOT_CHECK")).isEmpty();
        assertThat(HandoffNeed.fromWire("payment")).isEmpty();
        assertThat(HandoffNeed.fromWire("tab_check")).isEmpty();
        assertThat(HandoffNeed.values()).allSatisfy(need -> assertThat(need.title()).startsWith("「내 브라우저」"));
    }


    @Test
    @DisplayName("탭 없는 human_browser 는 빈 글로 서명을 확인하고 탭 없이 돌려준다. 탭 칸이 null 이어도 같다")
    void acceptsTablessHumanBrowser() {
        ObjectNode tabless = tabless();
        ObjectNode nullTab = tabless().putNull("tab");

        for (ObjectNode body : new ObjectNode[] {tabless, nullTab}) {
            ConnectorHandoffRequest request = ConnectorHandoffRequest.verify(body, KEY);
            assertThat(request.need()).isEqualTo(HandoffNeed.HUMAN_BROWSER);
            assertThat(request.tab()).isNull();
        }
    }


    @Test
    @DisplayName("탭 없는 넘기기는 human_browser 만 받고, 탭 없는 서명으로 탭을 붙이면 거절한다")
    void rejectsTablessOtherNeedsAndAddedTab() {
        ObjectNode login = body();
        login.remove("tab");
        ObjectNode addedTab = tabless().put("tab", "TAB1");
        for (ObjectNode bad : new ObjectNode[] {login, addedTab}) {
            assertThatThrownBy(() -> ConnectorHandoffRequest.verify(bad, KEY)).isInstanceOf(ApiException.class);
        }
    }


    @Test
    @DisplayName("서명이 틀리거나, 어휘 밖이거나, 탭이 모양 밖이거나, 칸이 빠지면 같은 오류로 거절한다")
    void rejectsBadBodies() {
        ObjectNode wrongSig = body().put("sig", "0".repeat(64));
        ObjectNode payment = body().put("need", "payment");
        ObjectNode badTab = body().put("tab", "../json/close");
        ObjectNode badCode = body().put("code", "lower");
        ObjectNode missing = body();
        missing.remove("tool_call_id");
        ObjectNode wrongVersion = body().put("v", 2);
        for (ObjectNode bad : new ObjectNode[] {wrongSig, payment, badTab, badCode, missing, wrongVersion}) {
            assertThatThrownBy(() -> ConnectorHandoffRequest.verify(bad, KEY))
                .isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getMessage()).isEqualTo("connector handoff request is rejected"));
        }
        assertThatThrownBy(() -> ConnectorHandoffRequest.verify(body(), "")).isInstanceOf(ApiException.class);
    }


    private static ObjectNode body() {
        ObjectNode body = JSON.createObjectNode();
        body.put("v", 1);
        body.put("root_session_id", "fos-root-1");
        body.put("session_id", "fos-session-1");
        body.put("tool_call_id", "call_1");
        body.put("hermes_tool", "mcp__demo__write_note");
        body.put("code", "DEMO_LOGIN_REQUIRED");
        body.put("need", "login");
        body.put("tab", "TAB1");
        body.put("sig", SIG);
        return body;
    }


    private static ObjectNode tabless() {
        ObjectNode body = body();
        body.remove("tab");
        body.put("code", "DEMO_BROWSER_IN_USE");
        body.put("need", "human_browser");
        body.put("sig", TABLESS_SIG);
        return body;
    }
}
