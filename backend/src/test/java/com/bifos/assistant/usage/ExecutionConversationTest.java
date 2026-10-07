package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionConversation;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** 대화가 실행 줄에 넘기는 값이 대화 번호와 effort 출처 판정을 그대로 전하는지 확인한다. */
@BackendIntegrationTest
class ExecutionConversationTest {

    private static final Long USER_ID = 4_173L;

    private static final String PROVIDER = "example-provider";

    private static final String MODEL = "example-agent";

    /** 대화가 고른 effort 다. Hermes 에 보낸 effort 와 다른 값으로 두어 어느 쪽을 읽었는지 구분한다. */
    private static final String CHOSEN_EFFORT = "high";

    /** Hermes 에 보낸 값이다. 단계 없이 보내므로 출처는 대화가 effort 를 골랐는지로만 정해진다. */
    private static final ModelChoice SENT = ModelChoice.stored(PROVIDER, MODEL, "medium");

    @Autowired
    ExecutionRecorder recorder;

    /** 세션 조회를 하지 않는다. 시작할 때 적는 값만 보는 검사다. */
    @MockitoBean
    HermesRunsClient hermes;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    TransactionTemplate transaction;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "실행", null, Instant.now()));
    }

    @Test
    @DisplayName("대화가 effort 를 골랐으면 그 값을 담아 내고 실행 줄의 출처는 REQUESTED 다")
    void carriesEffortChosenByConversationAndRecordsRequestedSource() {
        transaction.executeWithoutResult(status -> conversations.chooseModelIfActive(
                conversation.id(), USER_ID, PROVIDER, MODEL, CHOSEN_EFFORT, ModelSelectionMode.CUSTOM));
        Conversation chosen = conversations.findById(conversation.id()).orElseThrow();

        ExecutionConversation value = chosen.executionConversation();
        AgentExecution execution = start(value);

        assertThat(value.id()).isEqualTo(conversation.id());
        assertThat(value.reasoningEffort()).isEqualTo(CHOSEN_EFFORT);
        assertThat(saved(execution).conversationId()).isEqualTo(conversation.id());
        assertThat(saved(execution).reasoningEffortSource()).isEqualTo(ReasoningEffortSource.REQUESTED);
    }

    @Test
    @DisplayName("대화가 effort 를 고르지 않았으면 null 을 담아 내고 실행 줄의 출처는 AGENT DEFAULT 다")
    void carriesNullEffortWhenConversationChoseNoneAndRecordsAgentDefaultSource() {
        ExecutionConversation value = conversation.executionConversation();
        AgentExecution execution = start(value);

        assertThat(value.id()).isEqualTo(conversation.id());
        assertThat(value.reasoningEffort()).isNull();
        assertThat(saved(execution).conversationId()).isEqualTo(conversation.id());
        assertThat(saved(execution).reasoningEffortSource()).isEqualTo(ReasoningEffortSource.AGENT_DEFAULT);
    }

    @Test
    @DisplayName("대화 없이 시작한 실행 줄은 대화 번호가 비고 출처는 AGENT DEFAULT 다")
    void leavesConversationIdEmptyWhenStartedWithoutConversation() {
        AgentExecution execution = start(null);

        assertThat(saved(execution).conversationId()).isNull();
        assertThat(saved(execution).reasoningEffortSource()).isEqualTo(ReasoningEffortSource.AGENT_DEFAULT);
    }

    /** 단계 없이 effort 를 실어 보낸 실행을 시작한다. 인자 열둘을 받는 판이라야 보낸 값과 단계를 함께 정한다. */
    private AgentExecution start(ExecutionConversation value) {
        return recorder.start(
                user(),
                value,
                agent(),
                null,
                null,
                ExecutionContextSnapshot.ofChars(0L),
                SENT,
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
                USER_ID,
                Instant.now());
    }
}
