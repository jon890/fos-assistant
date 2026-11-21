package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.TurnOutcome;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.contentdraft.application.ContentDraftSaveApproval;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.quality.application.dto.CheckObservation;
import com.bifos.assistant.quality.application.dto.ToolObservation;
import com.bifos.assistant.quality.application.dto.TurnObservation;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.EvalBundle;
import com.bifos.assistant.quality.domain.EvalBundle.EvalExpect;
import com.bifos.assistant.quality.domain.EvalBundle.EvalQuestion;
import com.bifos.assistant.quality.domain.EvalBundle.EvalTurn;
import com.bifos.assistant.quality.domain.EvalBundle.PassRule;
import com.bifos.assistant.quality.domain.EvalResult.ItemResult;
import com.bifos.assistant.quality.domain.type.CardAction;
import com.bifos.assistant.quality.domain.type.EvalItemStatus;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 평가 실행기가 차례를 보내고 승인 카드를 처리한 뒤 판정하고 비우는 순서를 본다. Hermes 와 DB 없이 대역으로 본다. 대화를 실제로 거치는 흐름은 가짜 Hermes e2e
 * 가 본다.
 */
class EvalRunnerTest {

    private static final Long RUN = 1L;
    private static final Long CONVERSATION = 31L;
    private static final UUID CARD = UUID.randomUUID();

    private final EvalRuns runs = mock(EvalRuns.class);
    private final QualityRunRepository rows = mock(QualityRunRepository.class);
    private final EvalCleaner cleaner = mock(EvalCleaner.class);
    private final DeployCheckCleaner conversations = mock(DeployCheckCleaner.class);
    private final DeployCheckObserver observer = mock(DeployCheckObserver.class);
    private final TurnDriver driver = mock(TurnDriver.class);
    private final EvaluationUserService evaluationUsers = mock(EvaluationUserService.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ChatService chat = mock(ChatService.class);
    private final ConnectorActionRepository connectorActions = mock(ConnectorActionRepository.class);
    private final ConnectorActionService actionService = mock(ConnectorActionService.class);
    private final ContentDraftSaveApproval contentDrafts = mock(ContentDraftSaveApproval.class);
    private final ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
    private EvalRunner runner;
    private ConnectorAction card;


    @BeforeEach
    void setUp() {
        QualityProperties properties = new QualityProperties(Duration.ofMinutes(10), Duration.ofMillis(10), Duration.ofDays(30), "0 15 5 * * *",
            "classpath:quality/bundles/", Duration.ofSeconds(5));
        runner = new EvalRunner(runs, rows, cleaner, conversations, observer, driver, evaluationUsers, users, chat, mock(AttachmentService.class),
            mock(TaskService.class), connectorActions, actionService, contentDrafts, connections, mock(BackgroundTasks.class),
            LiveProperties.fixed(QualityProperties.class, properties), Clock.systemUTC());
        AppUser evaluation = AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER);
        when(users.findById(7L)).thenReturn(Optional.of(evaluation));
        Agent primary = mock(Agent.class);
        when(primary.code()).thenReturn("eval-primary");
        when(evaluationUsers.primaryAgent(any())).thenReturn(Optional.of(primary));
        when(evaluationUsers.delegateAgent(any())).thenReturn(Optional.empty());
        Conversation conversation = mock(Conversation.class);
        when(conversation.id()).thenReturn(CONVERSATION);
        when(conversation.publicId()).thenReturn(UUID.randomUUID());
        when(chat.startEmpty(any(), eq("eval-primary"))).thenReturn(conversation);
        when(driver.send(any(), eq(CONVERSATION), any(), anyList(), any())).thenReturn(TurnOutcome.DONE);
        when(driver.waitUntil(any(), any())).thenReturn(true);
        when(observer.delegationSettled(CONVERSATION)).thenReturn(true);
        when(observer.roots(CONVERSATION)).thenReturn(List.of());
        when(cleaner.rejectPending(any(), eq(CONVERSATION))).thenReturn(true);
        when(cleaner.cleanQuestion(any(), eq(CONVERSATION), any())).thenReturn(true);
        when(conversations.dropConversation(any(CurrentUser.class), eq(CONVERSATION))).thenReturn(true);
        when(contentDrafts.select(any(), any())).thenReturn(Optional.empty());
        card = mock(ConnectorAction.class);
        when(card.publicId()).thenReturn(CARD);
        answer("지울게요", "mcp__fos_assistant__task_delete");
    }


    @Test
    @DisplayName("둘째 차례가 띄운 카드를 승인하고 그 줄이 끝난 상태로 판정하며, 통과한 대화를 지운다")
    void approvesCardAndJudgesItsStatus() {
        pendingAfterSecondTurn();
        when(card.status()).thenReturn(ActionStatus.SUCCEEDED);
        when(connectorActions.findByPublicId(CARD)).thenReturn(Optional.of(card));

        runner.run(RUN, 7L, List.of(bundle(CardAction.APPROVE, "SUCCEEDED")), null);

        verify(actionService).approve(any(), eq(CARD), eq(null), eq(false));
        ItemResult result = lastItem();
        assertThat(result.status()).isEqualTo(EvalItemStatus.PASSED);
        assertThat(result.conversationPublicId()).isNull();
        verify(conversations).dropConversation(any(CurrentUser.class), eq(CONVERSATION));
        verify(runs).markCleaned(RUN);
    }


    @Test
    @DisplayName("reject 차례는 카드를 거절하고 기대한 상태와 다르면 규칙 미달이며 대화를 남긴다")
    void rejectsCard() {
        pendingAfterSecondTurn();
        when(card.status()).thenReturn(ActionStatus.REJECTED);
        when(connectorActions.findByPublicId(CARD)).thenReturn(Optional.of(card));

        runner.run(RUN, 7L, List.of(bundle(CardAction.REJECT, "SUCCEEDED")), null);

        verify(actionService).reject(any(), eq(CARD));
        ItemResult result = lastItem();
        assertThat(result.status()).isEqualTo(EvalItemStatus.FAILED);
        assertThat(result.failures()).containsExactly("CARD_STATUS:SUCCEEDED");
        assertThat(result.conversationPublicId()).isNotNull();
        verify(conversations, never()).dropConversation(any(CurrentUser.class), eq(CONVERSATION));
    }


    @Test
    @DisplayName("카드 처리를 적지 않은 차례의 카드는 누르지 않고 판정 뒤 거절로 닫는다")
    void leavesUnlistedCardForCleanup() {
        pendingAfterSecondTurn();

        runner.run(RUN, 7L, List.of(bundle(null, null)), null);

        verify(actionService, never()).approve(any(), any(), any(), anyBoolean());
        verify(actionService, never()).reject(any(), any());
        verify(cleaner).rejectPending(any(), eq(CONVERSATION));
        assertThat(lastItem().status()).isEqualTo(EvalItemStatus.PASSED);
    }


    @Test
    @DisplayName("원고 저장 승인 줄은 누르지 않고 CARD_FAILED 다")
    void contentDraftCardFails() {
        pendingAfterSecondTurn();
        when(contentDrafts.select(any(), eq(CARD))).thenReturn(Optional.of(UUID.randomUUID()));

        runner.run(RUN, 7L, List.of(bundle(CardAction.APPROVE, "SUCCEEDED")), null);

        verify(actionService, never()).approve(any(), any(), any(), anyBoolean());
        ItemResult result = lastItem();
        assertThat(result.status()).isEqualTo(EvalItemStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("CARD_FAILED");
    }


    @Test
    @DisplayName("한도 거절은 BUSY, 마감을 넘기면 TIMED_OUT 이고 남은 차례를 보내지 않는다")
    void busyAndTimeout() {
        when(driver.send(any(), eq(CONVERSATION), any(), anyList(), any())).thenReturn(TurnOutcome.BUSY);
        runner.run(RUN, 7L, List.of(bundle(null, null)), null);
        assertThat(lastItem().reasonCode()).isEqualTo("BUSY");
        verify(driver, times(1)).send(any(), eq(CONVERSATION), any(), anyList(), any());

        when(driver.send(any(), eq(CONVERSATION), any(), anyList(), any())).thenReturn(TurnOutcome.TIMED_OUT);
        runner.run(RUN, 7L, List.of(bundle(null, null)), null);
        assertThat(lastItem().status()).isEqualTo(EvalItemStatus.TIMED_OUT);
    }


    @Test
    @DisplayName("채울 자리 값이 없는 문항은 보내지 않고 건너뛴다")
    void skipsQuestionWithoutSlotValue() {
        EvalQuestion question = new EvalQuestion("agent-switch", null, List.of(new EvalTurn("{otherAgentName} 로 바꿔 줘", null, null)),
            EvalExpect.NONE, false);

        runner.run(RUN, 7L, List.of(new EvalBundle("b", "묶음", 10, new PassRule(PassRule.RATIO, 0.85), List.of(question))), null);

        assertThat(lastItem().status()).isEqualTo(EvalItemStatus.SKIPPED);
        verify(chat, never()).startEmpty(any(), any());
    }


    @Test
    @DisplayName("단계를 고르면 문항 대화의 단계를 그 값으로 바꾼다")
    void choosesTier() {
        runner.run(RUN, 7L, List.of(bundle(null, null)), ModelTier.HIGH);

        verify(chat).chooseModelTier(any(), eq(CONVERSATION), eq(ModelSelectionMode.TIER), eq(ModelTier.HIGH));
    }


    @Test
    @DisplayName("밖에서 채점하는 묶음은 규칙 판정 없이 답을 받으면 통과로 적고 대화를 남긴다")
    void externalBundleKeepsAnsweredConversation() {
        EvalQuestion question = new EvalQuestion("shop-vacuum", "쇼핑", List.of(new EvalTurn("청소기 추천해 줘", null, null)), EvalExpect.NONE, true);

        runner.run(RUN, 7L, List.of(new EvalBundle("answer-quality", "답변 품질", 15, new PassRule(PassRule.EXTERNAL, null), List.of(question))),
            null);

        ItemResult result = lastItem();
        assertThat(result.status()).isEqualTo(EvalItemStatus.PASSED);
        assertThat(result.conversationPublicId()).isNotNull();
        verify(conversations, never()).dropConversation(any(CurrentUser.class), eq(CONVERSATION));
    }


    private EvalBundle bundle(CardAction secondTurnCard, String cardStatus) {
        EvalQuestion question = new EvalQuestion("task-delete-card", "schedule",
            List.of(new EvalTurn("매일 아침 7시에 날씨 알려 줘", null, null), new EvalTurn("그 예약 지워 줘", null, secondTurnCard)),
            new EvalExpect(null, null, null, List.of("task_delete"), null, null, null, cardStatus), false);
        return new EvalBundle("b", "묶음", 10, new PassRule(PassRule.RATIO, 0.85), List.of(question));
    }


    private void pendingAfterSecondTurn() {
        when(connectorActions.findByConversationIdAndUserIdAndStatusOrderByIdAsc(eq(CONVERSATION), any(), eq(ActionStatus.PENDING)))
            .thenReturn(List.of())
            .thenReturn(List.of(card));
    }


    private void answer(String text, String toolName) {
        TurnObservation turn = new TurnObservation(text, "SUCCEEDED", null, List.of(new ToolObservation(toolName, false)), List.of(), List.of(),
            false);
        when(observer.observe(any(), eq(CONVERSATION), eq(false))).thenReturn(new CheckObservation(List.of(turn), 0, false, null, null, false));
    }


    private ItemResult lastItem() {
        ArgumentCaptor<ItemResult> items = ArgumentCaptor.forClass(ItemResult.class);
        verify(runs, atLeastOnce()).record(eq(RUN), items.capture(), anyInt());
        return items.getValue();
    }
}
