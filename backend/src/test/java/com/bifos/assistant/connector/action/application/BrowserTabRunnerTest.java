package com.bifos.assistant.connector.action.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.BrowserTabControl;
import com.bifos.assistant.browser.application.dto.AgentControlStatus;
import com.bifos.assistant.browser.application.dto.TabCloseResult;
import com.bifos.assistant.browser.application.dto.TabRef;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 승인한 탭 닫기와 열기가 실행 직전 판정을 지키고 결과 글에 탭 정보를 넣지 않는지 본다(docs/features/agent-browser/design.md). */
class BrowserTabRunnerTest {

    private static final CurrentUser USER = new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private final BrowserAgentControl control = mock(BrowserAgentControl.class);
    private final BrowserTabControl tabs = mock(BrowserTabControl.class);
    private final BrowserTabCloseRunner close = new BrowserTabCloseRunner(control, tabs);
    private final BrowserTabOpenRunner open = new BrowserTabOpenRunner(control, tabs);


    @BeforeEach
    void setUp() {
        when(control.status(USER)).thenReturn(AgentControlStatus.ALLOWED);
    }


    @Test
    @DisplayName("닫기 실행 칸을 읽어 닫고 결과 글에는 수만 적는다")
    void closesAndReportsCountsOnly() {
        when(tabs.close(7L, List.of(new TabRef("A1", "h1"), new TabRef("A2", "h2")))).thenReturn(new TabCloseResult(1, 1));

        ControlPlaneActionOutcome outcome = close.run(USER, Map.of(ControlPlaneActions.BROWSER_TARGETS, "A1:h1,A2:h2,broken", "tabs", "비밀 제목 · bank.example.com"));

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.message()).startsWith("탭 1개를 닫았어요").contains("탭 1개는").doesNotContain("비밀 제목", "bank.example.com", "A1");
    }


    @Test
    @DisplayName("스위치가 꺼졌으면 브라우저를 부르지 않고 실패로 끝낸다")
    void refusesWhenSwitchedOff() {
        when(control.status(USER)).thenReturn(AgentControlStatus.ADMIN_OFF);

        assertThat(close.run(USER, Map.of(ControlPlaneActions.BROWSER_TARGETS, "A1:h1")).succeeded()).isFalse();
        assertThat(open.run(USER, Map.of("url", "https://example.com/")).succeeded()).isFalse();
        verify(tabs, never()).close(anyLong(), anyList());
        verify(tabs, never()).open(anyLong(), any());
    }


    @Test
    @DisplayName("사람이 「직접 할게요」 로 쥐고 있으면 돌려준 뒤 다시 하라고 실패로 끝낸다")
    void failsWhileHumanHolds() {
        when(tabs.close(anyLong(), anyList())).thenThrow(new ApiException(ErrorCode.BROWSER_HUMAN_HELD, "held"));
        when(tabs.open(anyLong(), any())).thenThrow(new ApiException(ErrorCode.BROWSER_HUMAN_HELD, "held"));

        ControlPlaneActionOutcome closed = close.run(USER, Map.of(ControlPlaneActions.BROWSER_TARGETS, "A1:h1"));
        ControlPlaneActionOutcome opened = open.run(USER, Map.of("url", "https://example.com/"));

        assertThat(closed.succeeded()).isFalse();
        assertThat(closed.message()).isEqualTo(BrowserTabRunners.HELD);
        assertThat(opened.message()).isEqualTo(BrowserTabRunners.HELD);
    }


    @Test
    @DisplayName("닫은 탭이 없으면 실패로 끝내고, 브라우저를 켜지 못하면 다시 해 달라고 한다")
    void reportsNothingClosedAndStartFailure() {
        when(tabs.close(anyLong(), anyList())).thenReturn(new TabCloseResult(0, 2));
        when(tabs.open(anyLong(), any())).thenThrow(new ApiException(ErrorCode.BROWSER_CAPACITY, "full"));

        assertThat(close.run(USER, Map.of(ControlPlaneActions.BROWSER_TARGETS, "A1:h1,A2:h2")).succeeded()).isFalse();
        assertThat(open.run(USER, Map.of("url", "https://example.com/")).message()).contains("브라우저를 켤 수 없어요");
    }


    @Test
    @DisplayName("카드 값에서 닫기 실행 칸을 빼고 다른 동작의 인자는 그대로 둔다")
    void cardArgsHideTargets() {
        String args = "{\"tabs\":\"메일 · mail.example.com\",\"targets\":\"A1:h1\"}";

        assertThat(ControlPlaneActions.cardArgs(ControlPlaneActions.BROWSER_TAB_CLOSE, args))
            .contains("메일 · mail.example.com").doesNotContain("targets", "A1");
        assertThat(ControlPlaneActions.cardArgs(ControlPlaneActions.MEMORY_FORGET, args)).isEqualTo(args);
    }
}
