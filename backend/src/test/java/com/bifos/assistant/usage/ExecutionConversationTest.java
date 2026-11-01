package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.dto.ExecutionContextSnapshot;
import com.bifos.assistant.usage.execution.application.ExecutionRecorder;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionConversation;
import com.bifos.assistant.usage.execution.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 대화가 실행 줄에 넘기는 값이 대화 번호와 effort 출처 판정을 그대로 전하는지 확인한다. */
@BackendIntegrationTest
class ExecutionConversationTest {

    private static final Long USER_ID = 4_173L;

    private static final String PROVIDER = "example-provider";

    private static final String MODEL = "example-agent";

    /** Hermes 에 보낸 값이다. 에이전트 기본 effort 가 없어, 보낸 effort 가 있으면 출처는 언제나 REQUESTED 다. */
    private static final ModelChoice SENT = ModelChoice.stored(PROVIDER, MODEL, "medium");

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    private Conversation conversation;


    @BeforeEach
    void setUp() {
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "실행", null, Instant.now()));
    }


    @Test
    @DisplayName("대화가 effort 를 고르지 않았으면 null 을 담아 내고 보낸 effort 가 있으면 실행 줄의 출처는 REQUESTED 다")
    void carriesNullEffortWhenConversationChoseNoneAndRecordsRequestedSource() {
        ExecutionConversation value = conversation.executionConversation();
        AgentExecution execution = start(value);

        assertThat(value.id()).isEqualTo(conversation.id());
        assertThat(value.reasoningEffort()).isNull();
        assertThat(saved(execution).conversationId()).isEqualTo(conversation.id());
        assertThat(saved(execution).reasoningEffortSource()).isEqualTo(ReasoningEffortSource.REQUESTED);
    }


    @Test
    @DisplayName("대화 없이 시작한 실행 줄은 대화 번호가 비고 보낸 effort 가 있으면 출처는 REQUESTED 다")
    void leavesConversationIdEmptyWhenStartedWithoutConversation() {
        AgentExecution execution = start(null);

        assertThat(saved(execution).conversationId()).isNull();
        assertThat(saved(execution).reasoningEffortSource()).isEqualTo(ReasoningEffortSource.REQUESTED);
    }


    @Test
    @DisplayName("보낸 effort 가 없으면 실행 줄의 출처는 UNKNOWN 이다")
    void recordsUnknownSourceWhenNoEffortWasSent() {
        AgentExecution execution = start(conversation.executionConversation(), ModelChoice.defaults());

        assertThat(saved(execution).reasoningEffort()).isNull();
        assertThat(saved(execution).reasoningEffortSource()).isEqualTo(ReasoningEffortSource.UNKNOWN);
    }


    /** 단계 없이 effort 를 실어 보낸 실행을 시작한다. 인자 열둘을 받는 판이라야 보낸 값과 단계를 함께 정한다. */
    private AgentExecution start(ExecutionConversation value) {
        return start(value, SENT);
    }


    private AgentExecution start(ExecutionConversation value, ModelChoice sent) {
        return recorder.start(
            user(),
            value,
            agent(),
            null,
            null,
            ExecutionContextSnapshot.ofChars(0L),
            sent,
            null,
            null,
            null,
            null,
            null);
    }


    private AgentExecution saved(AgentExecution execution) {
        return executions.findById(execution.id()).orElseThrow();
    }


    private static CurrentUser user() {
        return new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    }


    private static Agent agent() {
        return Agent.of(
            "dad",
            "Dad",
            "dad",
            "http://127.0.0.1:1/p/dad",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            USER_ID);
    }
}
