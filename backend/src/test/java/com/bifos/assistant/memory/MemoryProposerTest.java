package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryProposalProperties;
import com.bifos.assistant.memory.application.MemoryProposer;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "assistant.memory.propose.enabled=true")
@ActiveProfiles("test")
@Import(MemoryProposerTest.StubRuntime.class)
class MemoryProposerTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    private static final CurrentUser USER = new CurrentUser(1L, "user@example.com", "user", 1L, UserRole.MEMBER);

    @Autowired
    MemoryProposer proposer;

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    HermesRunsClient hermes;

    private Agent agent;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        memories.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        ((StubHermesRunsClient) hermes).reset();
        agent = agents.save(Agent.of(
                "test",
                "검사",
                "test",
                "http://runtime.test",
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.PRIVATE,
                USER.id()));
        conversation = conversations.save(Conversation.startedBy(USER.id(), "대화", agent.id()));
    }

    @Test
    @DisplayName("제안을 만들면 부모와 뿌리 실행을 기록하고 PROPOSED로 저장한다")
    void proposalRecordsParentAndRootRunAndSavesAsProposed() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes)
                .willReturn(HermesRunResult.of(
                        "proposal",
                        "new",
                        "completed",
                        "{\"title\":\"선호\",\"content\":\"국수는 맵지 않게 먹는다\"}",
                        "model",
                        "provider",
                        TokenUsage.empty()));

        proposer.proposeFrom(USER, conversation, agent, parent, "국수 이야기");

        assertThat(memories.findAll()).singleElement().satisfies(memory -> {
            assertThat(memory.status().name()).isEqualTo("PROPOSED");
            assertThat(memory.proposedByExecutionId()).isNotNull();
        });
        assertThat(executions.findAll())
                .filteredOn(execution -> !execution.id().equals(parent.id()))
                .singleElement()
                .satisfies(child -> {
                    assertThat(child.parentExecutionId()).isEqualTo(parent.id());
                    assertThat(child.rootExecutionId()).isEqualTo(parent.id());
                    assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
                    assertThat(executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(
                                    java.util.List.of(child.id())))
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("제안 실행이 실패해도 예외를 던지지 않고 자식 실행은 실패로 남긴다")
    void keepsChildRunFailedWithoutThrowingWhenProposalRunFails() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes).willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        proposer.proposeFrom(USER, conversation, agent, parent, "답");

        assertThat(memories.findAll()).isEmpty();
        assertThat(executions.findAll())
                .filteredOn(execution -> !execution.id().equals(parent.id()))
                .singleElement()
                .satisfies(child -> {
                    assertThat(child.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(child.parentExecutionId()).isEqualTo(parent.id());
                    assertThat(child.rootExecutionId()).isEqualTo(parent.id());
                });
    }

    @Test
    @DisplayName("제안 실행의 provider 가 막히면 PROVIDER BLOCKED 로 남기고 예외를 던지지 않는다")
    void leavesProviderBlockedWithoutThrowingWhenProposalProviderIsBlocked() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes)
                .willReturn(new HermesRunResult(
                        "proposal",
                        "new",
                        "failed",
                        null,
                        "model",
                        "provider",
                        HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " every account is blocked",
                        TokenUsage.empty()));

        assertThatCode(() -> proposer.proposeFrom(USER, conversation, agent, parent, "답"))
                .doesNotThrowAnyException();

        assertThat(memories.findAll()).isEmpty();
        assertThat(executions.findAll())
                .filteredOn(execution -> !execution.id().equals(parent.id()))
                .singleElement()
                .satisfies(child -> {
                    assertThat(child.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(child.errorCode()).isEqualTo(ErrorCode.PROVIDER_BLOCKED.name());
                    assertThat(executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(
                                    java.util.List.of(child.id())))
                            .singleElement()
                            .satisfies(event -> {
                                assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
                                assertThat(event.detail()).isEqualTo(ErrorCode.PROVIDER_BLOCKED.name());
                            });
                });
    }

    @Test
    @DisplayName("NONE과 잘못된 JSON은 Memory를 만들지 않는다")
    void noneAndMalformedJsonCreateNoMemory() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes)
                .willReturn(HermesRunResult.of(
                        "proposal", "new", "completed", "NONE", "model", "provider", TokenUsage.empty()));
        proposer.proposeFrom(USER, conversation, agent, parent, "답");
        assertThat(memories.findAll()).isEmpty();
    }

    @Test
    @DisplayName("자식 실행을 시작하지 못해도 원래 대화에 예외를 전하지 않는다")
    void doesNotPropagateExceptionWhenChildRunCannotStart() {
        ExecutionRecorder failingRecorder = mock(ExecutionRecorder.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(failingRecorder)
                .start(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        MemoryProposer isolated = new MemoryProposer(
                new MemoryProposalProperties(true),
                mock(MemoryService.class),
                mock(HermesRunsClient.class),
                failingRecorder,
                mock(ExecutionEventRecorder.class),
                mock(ExecutionEventRepository.class),
                new ObjectMapper());
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);

        assertThatCode(() -> isolated.proposeFrom(USER, conversation, agent, parent, "답"))
                .doesNotThrowAnyException();
    }
}
