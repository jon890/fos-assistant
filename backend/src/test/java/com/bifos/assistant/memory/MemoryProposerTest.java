package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryProposalProperties;
import com.bifos.assistant.memory.application.MemoryProposer;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.CatalogPrice;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.domain.ModelPrice;
import com.bifos.assistant.usage.domain.PriceCatalog;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
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

    @MockitoBean
    PriceCatalog prices;

    @Autowired
    TransactionTemplate transaction;

    private Agent agent;
    private Conversation conversation;
    private AgentExecution parent;

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
                USER.id(), Instant.now()));
        conversation = conversations.save(Conversation.startedBy(USER.id(), "대화", agent.id(), Instant.now()));
    }

    /** 본 실행이 단계나 에이전트 기본 모델에서 해석한 값이다. */
    private static final ModelChoice RESOLVED = ModelChoice.stored("example-provider", "example-agent", "low");

    @Test
    @DisplayName("제안을 만들면 부모와 루트 실행을 기록하고 PROPOSED로 저장한다")
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

        proposer.proposeFrom(USER, conversation, agent, parent, "국수 이야기", RESOLVED);

        // 대화에는 고른 모델이 없다. 본 실행이 해석한 값을 그대로 보내야 단계와 에이전트 기본 모델이 빠지지 않는다.
        assertThat(((StubHermesRunsClient) hermes).received()).last().satisfies(command -> {
            assertThat(command.provider()).isEqualTo(RESOLVED.provider());
            assertThat(command.model()).isEqualTo(RESOLVED.model());
            assertThat(command.reasoningEffort()).isEqualTo(RESOLVED.reasoningEffort());
        });
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
                    assertThat(executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(child.id())))
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("대화가 고른 단계로 돈 실행의 제안 실행은 같은 단계와 REQUESTED 로 남는다")
    void proposalKeepsTierAndRequestedSourceOfRunChosenByConversationTier() {
        assertProposalInherits(
                ModelTier.BALANCED, false, ModelChoice.stored("example-provider", "example-balanced", "medium"));
        assertThat(lastProposal()).satisfies(child -> {
            assertThat(child.modelTier()).isEqualTo(ModelTier.BALANCED);
            assertThat(child.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.REQUESTED);
        });
    }

    @Test
    @DisplayName("내 기본 단계나 그룹 기본 단계로 정해진 실행도 실행 줄은 단계와 REQUESTED 라 같은 모양으로 남는다")
    void proposalKeepsTierAndRequestedSourceOfRunResolvedFromDefaultTier() {
        assertProposalInherits(ModelTier.DEEP, false, ModelChoice.stored("example-provider", "example-deep", "high"));
        assertThat(lastProposal()).satisfies(child -> {
            assertThat(child.modelTier()).isEqualTo(ModelTier.DEEP);
            assertThat(child.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.REQUESTED);
        });
    }

    @Test
    @DisplayName("단계 없이 에이전트 기본 effort 로 돈 실행의 제안 실행은 AGENT DEFAULT 로 남는다")
    void proposalKeepsAgentDefaultSourceOfRunWithoutTier() {
        assertProposalInherits(null, false, ModelChoice.stored("example-provider", "example-agent", "medium"));
        assertThat(lastProposal()).satisfies(child -> {
            assertThat(child.modelTier()).isNull();
            assertThat(child.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.AGENT_DEFAULT);
        });
    }

    @Test
    @DisplayName("단계 없이 대화가 직접 고른 effort 로 돈 실행의 제안 실행은 REQUESTED 로 남는다")
    void proposalKeepsRequestedSourceOfRunWithEffortChosenByConversation() {
        assertProposalInherits(null, true, ModelChoice.stored("example-provider", "example-agent", "high"));
        assertThat(lastProposal()).satisfies(child -> {
            assertThat(child.modelTier()).isNull();
            assertThat(child.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.REQUESTED);
        });
    }

    @Test
    @DisplayName("보낸 effort 가 없으면 원래 실행 줄이 이미 보완됐어도 제안 실행의 출처는 UNKNOWN 이다")
    void proposalWithoutSentEffortHasUnknownSourceEvenWhenRunWasBackfilled() {
        assertProposalInherits(null, false, ModelChoice.stored("example-provider", "example-agent", null), "medium");
        assertThat(parent.reasoningEffortSource())
                .as("보완된 원래 실행 줄의 출처")
                .isEqualTo(ReasoningEffortSource.PROFILE_DEFAULT);
        assertThat(lastProposal()).satisfies(child -> {
            assertThat(child.modelTier()).isNull();
            assertThat(child.reasoningEffort()).isNull();
            assertThat(child.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.UNKNOWN);
        });
    }

    @Test
    @DisplayName("보낸 값을 받지 못한 이어받는 실행은 세 값을 비우고 출처를 UNKNOWN 으로 남긴다")
    void inheritingRunWithoutRequestedChoiceLeavesValuesEmptyAndUnknownSource() {
        parent = recorder.start(USER, conversation, agent, null, null, 0L);

        AgentExecution child = recorder.startInheriting(USER, conversation, agent, parent, null);

        assertThat(child.provider()).isNull();
        assertThat(child.model()).isNull();
        assertThat(child.reasoningEffort()).isNull();
        assertThat(child.reasoningEffortSource()).isEqualTo(ReasoningEffortSource.UNKNOWN);
        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
    }

    private void assertProposalInherits(ModelTier tier, boolean conversationChoosesEffort, ModelChoice sent) {
        assertProposalInherits(tier, conversationChoosesEffort, sent, null);
    }

    /**
     * 원래 실행을 만들어 두고 그 실행에서 제안 실행을 돌린다. Hermes 에 보낸 값이 {@code sent} 와 같은지도 본다.
     *
     * @param backfilledEffort 제안 전에 원래 실행 줄에 보완해 둘 profile 기본 effort. null 이면 보완하지 않는다
     */
    private void assertProposalInherits(
            ModelTier tier, boolean conversationChoosesEffort, ModelChoice sent, String backfilledEffort) {
        Conversation source = conversation;
        if (conversationChoosesEffort) {
            transaction.executeWithoutResult(status -> conversations.chooseModelIfActive(
                    conversation.id(),
                    USER.id(),
                    sent.provider(),
                    sent.model(),
                    sent.reasoningEffort(),
                    ModelSelectionMode.CUSTOM));
            source = conversations.findById(conversation.id()).orElseThrow();
        }
        parent = recorder.start(
                USER,
                source,
                agent,
                null,
                null,
                ExecutionContextSnapshot.ofChars(0L),
                sent,
                null,
                null,
                null,
                tier,
                null);
        if (backfilledEffort != null) {
            parent.recordProfileReasoningDefault(backfilledEffort);
            parent = executions.save(parent);
        }
        ((StubHermesRunsClient) hermes)
                .willReturn(HermesRunResult.of(
                        "proposal", "new", "completed", "NONE", "model", "provider", TokenUsage.empty()));

        proposer.proposeFrom(USER, source, agent, parent, "답", sent);

        assertThat(((StubHermesRunsClient) hermes).received()).last().satisfies(command -> {
            assertThat(command.provider()).isEqualTo(sent.provider());
            assertThat(command.model()).isEqualTo(sent.model());
            assertThat(command.reasoningEffort()).isEqualTo(sent.reasoningEffort());
        });
    }

    private AgentExecution lastProposal() {
        return executions.findAll().stream()
                .filter(execution -> !execution.id().equals(parent.id()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("제안 실행이 실패해도 예외를 던지지 않고 자식 실행은 실패로 남긴다")
    void keepsChildRunFailedWithoutThrowingWhenProposalRunFails() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes).willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        proposer.proposeFrom(USER, conversation, agent, parent, "답", ModelChoice.defaults());

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

        assertThatCode(() -> proposer.proposeFrom(USER, conversation, agent, parent, "답", ModelChoice.defaults()))
                .doesNotThrowAnyException();

        assertThat(memories.findAll()).isEmpty();
        assertThat(executions.findAll())
                .filteredOn(execution -> !execution.id().equals(parent.id()))
                .singleElement()
                .satisfies(child -> {
                    assertThat(child.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(child.errorCode()).isEqualTo(ErrorCode.PROVIDER_BLOCKED.name());
                    assertThat(executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(child.id())))
                            .singleElement()
                            .satisfies(event -> {
                                assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
                                assertThat(event.detail()).isEqualTo(ErrorCode.PROVIDER_BLOCKED.name());
                            });
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "PROVIDER_BLOCKED"})
    @DisplayName("실패한 Memory 제안도 사용량과 실제 모델 비용을 보존하고 원래 대화에는 오류를 전하지 않는다")
    void preservesUsageAndCostWithoutThrowingWhenProposalReturnsFailure(String errorCode) {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        when(prices.find("served-provider", "served-model"))
                .thenReturn(Optional.of(new CatalogPrice(
                        new ModelPrice(new BigDecimal("5"), new BigDecimal("30"), new BigDecimal("0.5"), List.of()),
                        "test-pricing@2026-10-01")));
        String error = "PROVIDER_BLOCKED".equals(errorCode)
                ? HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " every account is blocked"
                : "provider stopped after generating tokens";
        ((StubHermesRunsClient) hermes)
                .willReturn(new HermesRunResult(
                        "proposal",
                        "new",
                        "failed",
                        "{\"title\":\"선호\",\"content\":\"저장하면 안 되는 답\"}",
                        "echoed-model",
                        "echoed-provider",
                        error,
                        new TokenUsage(1_000L, 800L, 500L, 1_500L),
                        new SessionRuntime("served-model", "served-provider")));

        assertThatCode(() -> proposer.proposeFrom(USER, conversation, agent, parent, "답", RESOLVED))
                .doesNotThrowAnyException();

        assertThat(memories.findAll()).isEmpty();
        assertThat(((StubHermesRunsClient) hermes).received()).hasSize(1);
        assertThat(executions.findAll())
                .filteredOn(execution -> !execution.id().equals(parent.id()))
                .singleElement()
                .satisfies(child -> {
                    assertThat(child.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(child.errorCode()).isEqualTo(errorCode);
                    assertThat(child.hermesRunId()).isEqualTo("proposal");
                    assertThat(child.parentExecutionId()).isEqualTo(parent.id());
                    assertThat(child.rootExecutionId()).isEqualTo(parent.id());
                    assertThat(child.provider()).isEqualTo("served-provider");
                    assertThat(child.model()).isEqualTo("served-model");
                    assertThat(child.inputTokens()).isEqualTo(1_000L);
                    assertThat(child.cachedInputTokens()).isEqualTo(800L);
                    assertThat(child.outputTokens()).isEqualTo(500L);
                    assertThat(child.totalTokens()).isEqualTo(1_500L);
                    assertThat(child.estimatedCostMicros()).isEqualTo(16_400L);
                    assertThat(child.actualCostMicros()).isEqualTo(16_400L);
                    assertThat(child.costCurrency()).isEqualTo("USD");
                    assertThat(child.pricingVersion()).isEqualTo("test-pricing@2026-10-01");
                    assertThat(child.finishedAt()).isNotNull();
                });
        assertThat(executions.findById(parent.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("NONE과 잘못된 JSON은 Memory를 만들지 않는다")
    void noneAndMalformedJsonCreateNoMemory() {
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);
        ((StubHermesRunsClient) hermes)
                .willReturn(HermesRunResult.of(
                        "proposal", "new", "completed", "NONE", "model", "provider", TokenUsage.empty()));
        proposer.proposeFrom(USER, conversation, agent, parent, "답", ModelChoice.defaults());
        assertThat(memories.findAll()).isEmpty();
    }

    @Test
    @DisplayName("자식 실행을 시작하지 못해도 원래 대화에 예외를 전하지 않는다")
    void doesNotPropagateExceptionWhenChildRunCannotStart() {
        ExecutionRecorder failingRecorder = mock(ExecutionRecorder.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(failingRecorder)
                .startInheriting(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());
        MemoryProposer isolated = new MemoryProposer(
                new MemoryProposalProperties(true),
                mock(MemoryService.class),
                mock(HermesRunsClient.class),
                failingRecorder,
                mock(ExecutionEventRecorder.class),
                mock(ExecutionEventRepository.class),
                new ObjectMapper());
        AgentExecution parent = recorder.start(USER, conversation, agent, null, null, 0L);

        assertThatCode(() -> isolated.proposeFrom(USER, conversation, agent, parent, "답", ModelChoice.defaults()))
                .doesNotThrowAnyException();
    }
}
