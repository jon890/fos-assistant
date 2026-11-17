package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.orchestration.NativeDelegationSweepTest.Fixture;
import com.bifos.assistant.orchestration.application.NativeDelegationSweep;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.NativeResultFixtures;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 깨우기 설정이 꺼져 있으면 훑기가 알림 줄을 남기지 않는다(스펙 REQ14, DECISION39). 검사 설정의 기본은 꺼짐이다. */
@BackendIntegrationTest
class NativeDelegationSweepDisabledTest {

    @Autowired
    NativeDelegationSweep sweep;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;


    @Test
    @DisplayName("설정이 꺼져 있으면 기한을 넘은 자식이 있어도 훑기가 아무것도 남기지 않는다")
    void disabledSweepLeavesNothing() throws InterruptedException {
        Instant now = Instant.now();
        Fixture fixture = Fixture.create(jdbc, users, agents, conversations, executions, now);
        AgentExecution origin = fixture.origin(ExecutionStatus.SUCCEEDED);
        NativeResultFixtures.binding(
            jdbc, NativeDelegationSweepTest.PROFILE, fixture.dad().id(), origin.id(), true, now.minusSeconds(61 * 60), null, null, null, null);

        sweep.sweep();
        Thread.sleep(200);

        assertThat(messages.findByConversationIdOrderByIdAsc(fixture.conversation().id())).isEmpty();
    }
}
