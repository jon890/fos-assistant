package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionSignal;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.application.model.CardStatus;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.attention.presentation.AttentionController;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원래 기록을 읽어 카드 넷을 계산하는 흐름을 실제 DB 로 본다. 규칙은 {@code docs/backend/attention.md} 의 「후보와 trigger」 다.
 *
 * <p>시각은 이 검사의 시계가 정한다. 사용자는 검사마다 새로 만들어 다른 검사의 줄과 섞이지 않게 하고, 끝나면 그 사용자의 줄을
 * 지운다. 커넥터 카탈로그는 대역이 답한다.
 */
@BackendIntegrationTest
@Import(AttentionTestCandidates.class)
class AttentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");

    private static final String CONNECTOR = "attention-notes";
    private static final String WRITE = "write_note";

    /** 승인을 받는 쓰기 도구 하나를 선언한다. 승인 줄의 이름이 이 선언에서 온다. */
    private static final ConnectorManifest MANIFEST = new ConnectorManifest(
            CONNECTOR,
            "먼저 알리기 검사용 메모",
            "",
            List.of(),
            "list_scopes",
            "demo",
            List.of(),
            false,
            2,
            List.of(
                    new ConnectorTool("list_scopes", "READ", "none", null, null),
                    new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", null)));

    @Autowired
    TestClock clock;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    AttentionService service;

    @Autowired
    AttentionController controller;

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
    ResultDeliveryRepository deliveries;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();
    private CurrentUser dad;
    private Agent chief;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        AttentionTestCandidates.FAILING.failing = false;
        when(connector.readCatalog()).thenReturn(List.of(MANIFEST));
        dad = member();
        chief = agentOf(dad, "집안일 도우미");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AttentionTestCandidates.FAILING.failing = false;
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM attention_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM attention_control WHERE user_id = ?", userId);
            String ownConversations = "(SELECT id FROM conversation WHERE user_id = ?)";
            jdbc.update(
                    "DELETE FROM result_delivery_attempt WHERE delivery_id IN"
                            + " (SELECT id FROM result_delivery WHERE conversation_id IN " + ownConversations + ")",
                    userId);
            jdbc.update(
                    "DELETE FROM result_delivery_item WHERE delivery_id IN"
                            + " (SELECT id FROM result_delivery WHERE conversation_id IN " + ownConversations + ")",
                    userId);
            jdbc.update("DELETE FROM result_delivery WHERE conversation_id IN " + ownConversations, userId);
            jdbc.update("DELETE FROM connector_action WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
            jdbc.update(
                    "DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)",
                    userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("사용자 글 뒤에 실패한 루트 실행은 실패 카드에 NOT_RETRIED 와 실행 출처를 달고 NOW 로 보인다")
    void showsUserTurnFailureAsNow() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        AgentExecution failed = failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));

        AttentionView view = service.view(dad);

        AttentionItem item = onlyItem(view, CardKey.FAILURES);
        assertThat(item.itemKey()).isEqualTo("conversation:" + conversation.publicId());
        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(item.conversationId()).isEqualTo(conversation.publicId());
        assertThat(item.agentName()).isEqualTo("집안일 도우미");
        assertThat(item.why().signals()).containsExactly(AttentionSignal.NOT_RETRIED);
        assertThat(item.why().sources())
                .extracting(AttentionSourceRef::source, AttentionSourceRef::ref)
                .containsExactly(tuple("EXECUTION_STATE", "execution:" + failed.id()));
        assertThat(view.nowCount()).isEqualTo(1);
        assertThat(card(view, CardKey.CONTINUE).items())
                .as("실패 카드에 남은 대화는 이어서 하기에서 빠진다")
                .isEmpty();
    }

    @Test
    @DisplayName("같은 대화에서 그 뒤 성공한 루트 실행이 있으면 실패 항목이 없다")
    void dropsFailureRetriedWithSuccess() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "다시 해 줘", NOW.minusSeconds(300)));
        executions.save(
                root(dad, conversation, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(300), NOW.minusSeconds(240)));

        assertThat(card(service.view(dad), CardKey.FAILURES).items()).isEmpty();
    }

    @Test
    @DisplayName("시작 전 마지막 메시지가 알림 줄인 자동 turn 의 실패는 실패 카드에 없다")
    void skipsAutoTurnFailure() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(900)));
        message(ChatMessage.fromAssistant(conversation.id(), "맡겨 둘게요", null, NOW.minusSeconds(880)));
        message(ChatMessage.fromSystem(conversation.id(), "맡긴 일이 끝났다", NOW.minusSeconds(600)));
        failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));

        assertThat(card(service.view(dad), CardKey.FAILURES).items()).isEmpty();
    }

    @Test
    @DisplayName("알림 줄 뒤에 지시를 사용자 글로 저장한 예약 turn 의 실패는 실패 카드에 있다")
    void showsScheduledTurnFailure() {
        Conversation conversation = conversationOf(dad, "아침 날씨 알림");
        message(ChatMessage.fromSystem(conversation.id(), "예약 작업이 시작됐다", NOW.minusSeconds(600)));
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "오늘 날씨를 알려 줘", NOW.minusSeconds(600)));
        failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));

        assertThat(onlyItem(service.view(dad), CardKey.FAILURES).conversationId())
                .isEqualTo(conversation.publicId());
    }

    @Test
    @DisplayName("답 메시지 앞이 사용자 글인 다시 생성의 실패는 실패 카드에 있다")
    void showsRegenerateFailure() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(900)));
        message(ChatMessage.fromAssistant(conversation.id(), "정리했어요", null, NOW.minusSeconds(880)));
        failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));

        assertThat(onlyItem(service.view(dad), CardKey.FAILURES).conversationId())
                .isEqualTo(conversation.publicId());
    }

    @Test
    @DisplayName("다른 사용자의 실패는 응답에 없다")
    void hidesOtherUsersFailure() {
        CurrentUser mom = member();
        Conversation conversation = conversationOf(mom, "엄마의 대화");
        message(ChatMessage.fromUser(conversation.id(), mom.id(), "정리해 줘", NOW.minusSeconds(600)));
        failedRoot(mom, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));

        AttentionView view = service.view(dad);

        assertThat(card(view, CardKey.FAILURES).items()).isEmpty();
        assertThat(view.nowCount()).isZero();
    }

    @Test
    @DisplayName("끝난 지 8일 지난 실패는 응답에 없다")
    void skipsFailureOutsideWindow() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        Instant started = NOW.minus(Duration.ofDays(8)).minusSeconds(60);
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", started));
        failedRoot(dad, conversation, started, NOW.minus(Duration.ofDays(8)));

        assertThat(card(service.view(dad), CardKey.FAILURES).items()).isEmpty();
    }

    @Test
    @DisplayName("FAILED 결과 전달 묶음은 실패 카드에 DELIVERY_FAILED 와 DELIVERY_NOT_DONE 과 묶음 출처를 달고 보인다")
    void showsFailedDelivery() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        ResultDelivery delivery = deliveryOf(conversation, DeliveryStatus.FAILED, NOW);

        AttentionView view = service.view(dad);

        AttentionItem item = onlyItem(view, CardKey.FAILURES);
        assertThat(item.itemKey()).isEqualTo("conversation:" + conversation.publicId());
        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(item.conversationId()).isEqualTo(conversation.publicId());
        assertThat(item.why().trigger()).isEqualTo(AttentionTrigger.DELIVERY_FAILED);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.DELIVERY_NOT_DONE);
        assertThat(item.why().sources())
                .extracting(AttentionSourceRef::source, AttentionSourceRef::ref, AttentionSourceRef::asOf)
                .containsExactly(tuple("RESULT_DELIVERY", "result_delivery:" + delivery.id(), NOW));
        assertThat(view.nowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 대화에 실패한 사용자 turn 도 있으면 항목 하나에 신호가 둘 다 들고 더 최근인 쪽이 trigger 다")
    void mergesFailedTurnAndFailedDelivery() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        AgentExecution failed = failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));
        ResultDelivery delivery = deliveryOf(conversation, DeliveryStatus.FAILED, NOW.minusSeconds(60));

        AttentionItem item = onlyItem(service.view(dad), CardKey.FAILURES);

        assertThat(item.why().trigger()).isEqualTo(AttentionTrigger.DELIVERY_FAILED);
        assertThat(item.why().signals())
                .containsExactly(AttentionSignal.NOT_RETRIED, AttentionSignal.DELIVERY_NOT_DONE);
        assertThat(item.why().sources())
                .extracting(AttentionSourceRef::source, AttentionSourceRef::ref)
                .containsExactly(
                        tuple("EXECUTION_STATE", "execution:" + failed.id()),
                        tuple("RESULT_DELIVERY", "result_delivery:" + delivery.id()));
        assertThat(item.at()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("결과 전달 실패만 바뀌어도 stateKey 가 바뀌어 숨긴 항목이 다시 보인다")
    void changesStateKeyWhenDeliveryAttemptsGrow() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        ResultDelivery delivery = deliveryOf(conversation, DeliveryStatus.FAILED, NOW.minusSeconds(60));
        String before = onlyItem(service.view(dad), CardKey.FAILURES).stateKey();
        jdbc.update("UPDATE result_delivery SET attempt_count = 2 WHERE id = ?", delivery.id());

        String after = onlyItem(service.view(dad), CardKey.FAILURES).stateKey();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("stateKey 는 실패한 turn 만이면 실행 번호, 결과 전달만이면 묶음 번호와 시도 수, 둘 다면 실행 쪽 앞 글에 둘을 이은 글의 지문이다")
    void derivesStateKeyFromFailureMaterial() {
        Conversation turnOnly = conversationOf(dad, "실패한 turn 만 있는 대화");
        message(ChatMessage.fromUser(turnOnly.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        AgentExecution turnOnlyFailed = failedRoot(dad, turnOnly, NOW.minusSeconds(600), NOW.minusSeconds(540));
        Conversation deliveryOnly = conversationOf(dad, "결과 전달만 실패한 대화");
        ResultDelivery deliveryOnlyFailed = deliveryOf(deliveryOnly, DeliveryStatus.FAILED, NOW.minusSeconds(120));
        Conversation both = conversationOf(dad, "둘 다 실패한 대화");
        message(ChatMessage.fromUser(both.id(), dad.id(), "일정 정리해 줘", NOW.minusSeconds(500)));
        AgentExecution bothFailed = failedRoot(dad, both, NOW.minusSeconds(500), NOW.minusSeconds(450));
        ResultDelivery bothDelivery = deliveryOf(both, DeliveryStatus.FAILED, NOW.minusSeconds(60));

        List<AttentionItem> items = card(service.view(dad), CardKey.FAILURES).items();

        assertThat(items)
                .extracting(AttentionItem::conversationId, AttentionItem::stateKey)
                .containsExactlyInAnyOrder(
                        tuple(turnOnly.publicId(), Sha256.hex16("EXECUTION_FAILED|" + turnOnlyFailed.id())),
                        tuple(
                                deliveryOnly.publicId(),
                                Sha256.hex16("DELIVERY_FAILED|" + deliveryOnlyFailed.id() + "|1")),
                        tuple(
                                both.publicId(),
                                Sha256.hex16("EXECUTION_FAILED|" + bothFailed.id() + "|DELIVERY_FAILED|"
                                        + bothDelivery.id() + "|1")));
    }

    @Test
    @DisplayName("DELIVERING 과 STOPPED 와 DELIVERED 묶음은 실패 카드에 없다")
    void skipsNonFailedDeliveries() {
        deliveryOf(conversationOf(dad, "전달 중인 대화"), DeliveryStatus.DELIVERING, NOW.minusSeconds(60));
        deliveryOf(conversationOf(dad, "멈춘 대화"), DeliveryStatus.STOPPED, NOW.minusSeconds(60));
        deliveryOf(conversationOf(dad, "전달된 대화"), DeliveryStatus.DELIVERED, NOW.minusSeconds(60));

        assertThat(card(service.view(dad), CardKey.FAILURES).items()).isEmpty();
    }

    @Test
    @DisplayName("끝난 지 8일 지난 FAILED 묶음은 실패 카드에 없다")
    void skipsFailedDeliveryOutsideWindow() {
        deliveryOf(
                conversationOf(dad, "주간 장보기 목록 정리"),
                DeliveryStatus.FAILED,
                NOW.minus(Duration.ofDays(8)).minusSeconds(60));

        assertThat(card(service.view(dad), CardKey.FAILURES).items()).isEmpty();
    }

    @Test
    @DisplayName("다시 전달로 DELIVERED 가 된 뒤 사용자 turn 이 실패하면 그 실패는 EXECUTION_FAILED 로 남는다")
    void keepsUserTurnFailureAfterDeliveredRetry() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        deliveryOf(conversation, DeliveryStatus.DELIVERED, NOW.minusSeconds(600));
        executions.save(
                root(dad, conversation, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(600), NOW.minusSeconds(580)));
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "다음 목록도 정리해 줘", NOW.minusSeconds(300)));
        failedRoot(dad, conversation, NOW.minusSeconds(300), NOW.minusSeconds(240));

        AttentionItem item = onlyItem(service.view(dad), CardKey.FAILURES);

        assertThat(item.why().trigger()).isEqualTo(AttentionTrigger.EXECUTION_FAILED);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.NOT_RETRIED);
    }

    @Test
    @DisplayName("다른 사용자 대화의 FAILED 묶음은 응답에 없다")
    void hidesOtherUsersFailedDelivery() {
        CurrentUser mom = member();
        deliveryOf(conversationOf(mom, "엄마의 대화"), DeliveryStatus.FAILED, NOW.minusSeconds(60));

        AttentionView view = service.view(dad);

        assertThat(card(view, CardKey.FAILURES).items()).isEmpty();
        assertThat(view.nowCount()).isZero();
    }

    @Test
    @DisplayName("지운 대화의 FAILED 묶음은 응답에 없다")
    void hidesFailedDeliveryOfDeletedConversation() {
        Conversation conversation = conversationOf(dad, "지운 대화");
        deliveryOf(conversation, DeliveryStatus.FAILED, NOW.minusSeconds(60));
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), conversation.id());

        assertThat(card(service.view(dad), CardKey.FAILURES).items()).isEmpty();
    }

    @Test
    @DisplayName("기한이 3시간 남은 승인 줄은 나를 기다리는 카드에 EXPIRES_SOON 과 승인 줄 식별자와 대화를 달고 보인다")
    void showsPendingApprovalExpiringSoon() {
        Conversation conversation = conversationOf(dad, "메모 남기기");
        UUID actionId = insertPendingAction(dad, conversation.id(), NOW.plus(Duration.ofHours(3)));

        AttentionItem item = onlyItem(service.view(dad), CardKey.NEEDS_ME);

        assertThat(item.itemKey()).isEqualTo("connector_action:" + actionId);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.EXPIRES_SOON);
        assertThat(item.actionId()).isEqualTo(actionId);
        assertThat(item.conversationId()).isEqualTo(conversation.publicId());
        assertThat(item.title()).isEqualTo("메모 쓰기");
        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
    }

    @Test
    @DisplayName("대화가 없는 승인 줄은 응답에 없다")
    void skipsApprovalWithoutConversation() {
        insertPendingAction(dad, null, NOW.plus(Duration.ofHours(3)));

        assertThat(card(service.view(dad), CardKey.NEEDS_ME).items()).isEmpty();
    }

    @Test
    @DisplayName("40분 전에 시작한 위임 실행은 맡긴 일 카드에 LONG_RUNNING 을 달고 NOW 로 보이며 실행 칸을 채운다")
    void showsLongRunningDelegationAsNow() {
        Conversation conversation = conversationOf(dad, "여행 일정 짜기");
        AgentExecution parent = executions.save(
                root(dad, conversation, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(2_500), NOW.minusSeconds(2_450)));
        AgentExecution delegation = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .parentExecutionId(parent.id())
                .rootExecutionId(parent.id())
                .delegationKey("attention-" + UUID.randomUUID())
                .profileName(chief.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(NOW.minus(Duration.ofMinutes(40)))
                .build());

        AttentionItem item = onlyItem(service.view(dad), CardKey.DELEGATED);

        assertThat(item.itemKey()).isEqualTo("execution:" + delegation.id());
        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.LONG_RUNNING);
        assertThat(item.execution().id()).isEqualTo(delegation.id());
        assertThat(item.execution().status()).isEqualTo("RUNNING");
        assertThat(item.followUp()).isNull();
    }

    @Test
    @DisplayName("한 출처의 기록 읽기가 실패하면 그 카드만 UNAVAILABLE 이고 나머지 카드는 그대로다")
    void marksOnlyFailedSourceCardUnavailable() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));
        insertPendingAction(dad, conversation.id(), NOW.plus(Duration.ofHours(3)));
        AttentionTestCandidates.FAILING.failing = true;

        AttentionView view = service.view(dad);

        assertThat(card(view, CardKey.NEEDS_ME).status()).isEqualTo(CardStatus.UNAVAILABLE);
        assertThat(card(view, CardKey.NEEDS_ME).items()).isEmpty();
        assertThat(card(view, CardKey.FAILURES).status()).isEqualTo(CardStatus.OK);
        assertThat(card(view, CardKey.FAILURES).items()).hasSize(1);
        assertThat(card(view, CardKey.DELEGATED).status()).isEqualTo(CardStatus.OK);
        assertThat(card(view, CardKey.CONTINUE).status()).isEqualTo(CardStatus.OK);
        assertThat(view.nowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("경로의 응답 글에는 실행의 오류 코드, 모델, 금액이 없다")
    void responseHasNoErrorCodeModelOrCost() {
        Conversation conversation = conversationOf(dad, "주간 장보기 목록 정리");
        message(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        failedRoot(dad, conversation, NOW.minusSeconds(600), NOW.minusSeconds(540));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(dad, null));

        String body = JsonMapper.builder().build().writeValueAsString(controller.view());

        assertThat(body).contains("\"key\":\"failures\"", "\"attention\":\"NOW\"", "\"channel\":\"IN_APP\"");
        assertThat(body)
                .doesNotContain("errorCode", "\"model\"", "estimatedCostMicros", "HERMES_BUSY", "example-model");
    }

    private CurrentUser member() {
        String email = "attention-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent agentOf(CurrentUser owner, String name) {
        String code = "attention-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
    }

    private Conversation conversationOf(CurrentUser owner, String title) {
        return conversations.save(Conversation.startedBy(owner.id(), title, chief.id(), NOW.minusSeconds(3_600)));
    }

    /** 열린 묶음을 저장하고 상태와 바뀐 시각을 그 값으로 맞춘다. */
    private ResultDelivery deliveryOf(Conversation conversation, DeliveryStatus status, Instant updatedAt) {
        ResultDelivery delivery = deliveries.save(ResultDelivery.opened(conversation.id(), updatedAt));
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> deliveries.changeStatus(delivery.id(), status, updatedAt));
        return delivery;
    }

    private void message(ChatMessage message) {
        messages.save(message);
    }

    /** 오류 코드와 모델을 채운 실패한 대화 turn 루트 실행이다. 응답에 이 값이 새지 않는지 함께 본다. */
    private AgentExecution failedRoot(CurrentUser owner, Conversation conversation, Instant started, Instant finished) {
        return executions.save(root(owner, conversation, ExecutionStatus.FAILED, started, finished));
    }

    private AgentExecution root(
            CurrentUser owner, Conversation conversation, ExecutionStatus status, Instant started, Instant finished) {
        return AgentExecution.builder()
                .userId(owner.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .profileName(chief.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .model("example-model")
                .status(status)
                .errorCode(status == ExecutionStatus.FAILED ? "HERMES_BUSY" : null)
                .timing(started, finished)
                .build();
    }

    /** 이 검사의 커넥터로 답을 기다리는 승인 줄 하나를 넣는다. 승인 줄을 만드는 길은 커넥터 검사가 본다. */
    private UUID insertPendingAction(CurrentUser owner, Long conversationId, Instant expiresAt) {
        UUID actionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,
                    approval_mode, decision, passed, status, origin_execution_id, conversation_id, dedupe_key,
                    args_json, args_sha256, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, 'mcp__demo__write_note', 'WRITE', 'REQUIRED', 'NEEDS_APPROVAL',
                    FALSE, 'PENDING', 0, ?, ?, '{"text":"안녕"}', 'sha', ?, ?)
                """,
                bytes(actionId),
                owner.id(),
                chief.id(),
                CONNECTOR,
                WRITE,
                conversationId,
                UUID.randomUUID().toString(),
                Timestamp.from(expiresAt),
                Timestamp.from(NOW.minusSeconds(60)));
        return actionId;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static AttentionCard card(AttentionView view, CardKey key) {
        return view.cards().stream()
                .filter(card -> card.key() == key)
                .findFirst()
                .orElseThrow(() -> new AssertionError("카드 " + key + " 가 응답에 없다: " + view.cards()));
    }

    private static AttentionItem onlyItem(AttentionView view, CardKey key) {
        List<AttentionItem> items = card(view, key).items();
        assertThat(items).as("카드 %s 의 항목", key).hasSize(1);
        return items.getFirst();
    }
}
