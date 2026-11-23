package com.bifos.assistant.connector.action.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.connection.application.SpecialistAgentCreator;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 「새 전문 에이전트를 만들어 붙이기」 승인 실행기의 인자 해석이다(docs/features/assistant-hub/spec.md 의 REQ9). */
class ConnectorAttachRunnerTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);


    @Test
    @DisplayName("배포 전에 뜬 옛 카드(에이전트 이름 없이 에이전트 번호만 있다)는 만들거나 붙이지 않고 다시 부탁해 달라고 알린다")
    void refusesLegacyCard() {
        SpecialistAgentCreator creator = mock(SpecialistAgentCreator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SpecialistAgentCreator> creators = mock(ObjectProvider.class);
        when(creators.getObject()).thenReturn(creator);
        ConnectorAttachRunner runner = new ConnectorAttachRunner(creators);

        ControlPlaneActionOutcome outcome = runner.run(
            DAD, Map.of("connector", "검사용 메모", "agent", "메모 비서", "connector_id", "notes", "agent_code", "dad"));

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.message()).isEqualTo("「검사용 메모」 를 붙이지 못했어요. 예전 요청이라 다시 부탁해 주세요");
        verifyNoInteractions(creator);
    }


    @Test
    @DisplayName("새 에이전트의 소개는 마법사 기본값과 같고, 상한을 넘는 소개는 비운다")
    void introductionMatchesWizardDefault() {
        AgentIntroduction introduction = ConnectorAttachRunner.introduction("Gmail 담당", "Gmail");

        assertThat(introduction.name()).isEqualTo("Gmail 담당");
        assertThat(introduction.description()).isEqualTo("Gmail 일을 맡아요");
        assertThat(introduction.icon()).isEqualTo(ConnectorAttachRunner.ICON);
        assertThat(ConnectorAttachRunner.introduction("긴 이름 담당", "가".repeat(60)).description()).isNull();
    }
}
