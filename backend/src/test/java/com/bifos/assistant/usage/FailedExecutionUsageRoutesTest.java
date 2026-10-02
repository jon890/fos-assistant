package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.orchestration.application.AgentRunner;
import com.bifos.assistant.orchestration.domain.RunSession;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.CatalogPrice;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.domain.ModelPrice;
import com.bifos.assistant.usage.domain.PriceCatalog;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 대화와 위임 경로가 Hermes 실패 결과의 사용량을 실제 실행 기록과 비용으로 보존하는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
class FailedExecutionUsageRoutesTest {

    private static final String RUN_ID = "run-failed-with-usage";
    private static final String PROVIDER = "served-provider";
    private static final String MODEL = "served-model";
    private static final String PRICING_VERSION = "test-pricing@2026-10-01";

    @Autowired
    ChatService chat;

    @Autowired
    AgentRunner runner;

    @Autowired
    ExecutionRecorder recorder;

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
    ExecutionEventRepository events;

    @MockitoBean
    HermesRunsClient hermes;

    @MockitoBean
    HermesRunEventStream eventStream;

    @MockitoBean
    PriceCatalog prices;

    private CurrentUser user;
    private Agent agent;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        AppUser saved = users.save(
                AppUser.of("failed-usage-" + unique + "@example.com", "사용자", 4_201L, UserRole.MEMBER, Instant.now()));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
        agent = Agent.of(
                "failed-usage-" + unique,
                "실패 사용량 검사",
                "failed-usage-" + unique,
                "http://agent-runtime.test/p/failed-usage",
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now());
        agent.changeDefaultModel("requested-provider", "requested-model", null);
        agent = agents.save(agent);
        conversation = conversations.save(Conversation.startedBy(user.id(), "실패 사용량", agent.id(), Instant.now()));
        when(hermes.submit(any())).thenReturn(RUN_ID);
        when(prices.find(PROVIDER, MODEL))
                .thenReturn(Optional.of(new CatalogPrice(
                        new ModelPrice(new BigDecimal("5"), new BigDecimal("30"), new BigDecimal("0.5"), List.of()),
                        PRICING_VERSION)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "PROVIDER_BLOCKED"})
    @DisplayName("일반 대화는 실패 응답의 사용량과 실제 모델 비용을 남기고 원래 오류를 전달한다")
    void preservesUsageAndCostWhenConversationReceivesFailedResult(String errorCode) {
        when(hermes.awaitCompletion(any(), any())).thenReturn(failedResult(errorCode));
        ErrorCode expected =
                "PROVIDER_BLOCKED".equals(errorCode) ? ErrorCode.PROVIDER_BLOCKED : ErrorCode.HERMES_RUN_FAILED;

        assertThatThrownBy(() -> chat.send(user, conversation.id(), "질문", null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(expected);

        assertThat(executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(execution -> assertFailedUsage(execution, errorCode));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .singleElement()
                .satisfies(message -> assertThat(message.role()).isEqualTo(MessageRole.USER));
        assertThat(chat.running(user, conversation.id()).running()).isFalse();
        verify(hermes).submit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "PROVIDER_BLOCKED"})
    @DisplayName("위임 실행은 실패 응답의 사용량과 실제 모델 비용을 남기고 실패 결과를 전달한다")
    void preservesUsageAndCostWhenDelegatedRunReceivesFailedResult(String errorCode) {
        when(hermes.awaitCompletion(any(), any())).thenReturn(failedResult(errorCode));
        AgentExecution parent = recorder.start(user, conversation, agent, null, null, 0L);
        RunSession session = RunSession.fresh();
        DelegationKey key =
                DelegationKey.of(agent.hermesProfile(), "fos-root", "fos-root", "call_" + UUID.randomUUID());

        AgentRunner.Run run;
        try {
            run = runner.run(
                    user,
                    conversation,
                    agent,
                    "위임한 일",
                    parent.id(),
                    parent.id(),
                    session,
                    execution -> {},
                    (execution, runId) -> {},
                    () -> false,
                    null,
                    key);
        } finally {
            recorder.fail(parent, "CHILD_FAILED");
        }

        AgentExecution execution = executions.findById(run.execution().id()).orElseThrow();
        assertFailedUsage(execution, errorCode);
        assertThat(execution.parentExecutionId()).isEqualTo(parent.id());
        assertThat(execution.rootExecutionId()).isEqualTo(parent.id());
        assertThat(execution.delegationKey()).isEqualTo(key.value());
        assertThat(execution.hermesSessionId()).isEqualTo(session.correlationSessionId());
        assertThat(run.result().succeeded()).isFalse();
        assertThat(run.result().errorCode()).isEqualTo(errorCode);
        assertThat(run.result().output()).isNull();
        verify(hermes).submit(any());
    }

    private void assertFailedUsage(AgentExecution execution, String errorCode) {
        assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.errorCode()).isEqualTo(errorCode);
        assertThat(execution.hermesRunId()).isEqualTo(RUN_ID);
        assertThat(execution.provider()).isEqualTo(PROVIDER);
        assertThat(execution.model()).isEqualTo(MODEL);
        assertThat(execution.inputTokens()).isEqualTo(1_000L);
        assertThat(execution.cachedInputTokens()).isEqualTo(800L);
        assertThat(execution.outputTokens()).isEqualTo(500L);
        assertThat(execution.totalTokens()).isEqualTo(1_500L);
        assertThat(execution.estimatedCostMicros()).isEqualTo(16_400L);
        assertThat(execution.actualCostMicros()).isEqualTo(16_400L);
        assertThat(execution.costCurrency()).isEqualTo("USD");
        assertThat(execution.pricingVersion()).isEqualTo(PRICING_VERSION);
        assertThat(execution.finishedAt()).isNotNull();
        assertThat(execution.latencyMs()).isNotNegative();
        assertThat(execution.outputText()).isNull();
        assertThat(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(execution.id())))
                .satisfiesExactly(
                        started -> assertThat(started.eventType()).isEqualTo(ExecutionEventType.RUN_STARTED),
                        failed -> {
                            assertThat(failed.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
                            assertThat(failed.detail()).isEqualTo(errorCode);
                        });
    }

    private static HermesRunResult failedResult(String errorCode) {
        String error = "PROVIDER_BLOCKED".equals(errorCode)
                ? HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account"
                : "provider stopped after generating tokens";
        return new HermesRunResult(
                RUN_ID,
                "session-failed",
                "failed",
                "끝나지 않은 답",
                "echoed-request-model",
                "echoed-request-provider",
                error,
                new TokenUsage(1_000L, 800L, 500L, 1_500L),
                new SessionRuntime(MODEL, PROVIDER));
    }
}
