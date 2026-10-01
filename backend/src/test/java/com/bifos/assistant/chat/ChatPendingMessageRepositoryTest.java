package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대기 메시지 저장소의 쿼리를 본다.
 *
 * <p>저장소는 트랜잭션을 열지 않으므로 호출을 {@link TransactionTemplate} 안에서 한다. 검사들이 H2 를 함께 써서
 * 남은 대기 행이 다른 검사에 보이지 않도록 앞뒤로 비운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatPendingMessageRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private static final long CONVERSATION = 910_001L;
    private static final long OTHER_CONVERSATION = 910_002L;
    private static final long USER = 7L;

    @Autowired
    ChatPendingMessageRepository pendingMessages;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions.executeWithoutResult(status -> pendingMessages.deleteAll());
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> pendingMessages.deleteAll());
    }

    @Test
    @DisplayName("대화의 대기 메시지만 넣은 순서로 읽는다")
    void findsOnlyRowsOfConversationInQueuedOrder() {
        queue(CONVERSATION, "첫째");
        queue(OTHER_CONVERSATION, "남의 글");
        queue(CONVERSATION, "둘째");

        List<ChatPendingMessage> rows =
                transactions.execute(status -> pendingMessages.findByConversationIdOrderByIdAsc(CONVERSATION));

        assertThat(rows).extracting(ChatPendingMessage::content).containsExactly("첫째", "둘째");
        assertThat(rows).extracting(ChatPendingMessage::conversationId).containsOnly(CONVERSATION);
        assertThat(rows).extracting(ChatPendingMessage::userId).containsOnly(USER);
        assertThat(rows).extracting(ChatPendingMessage::createdAt).containsOnly(NOW);
    }

    @Test
    @DisplayName("대기 메시지가 없는 대화는 빈 목록을 준다")
    void findsNothingForConversationWithoutRows() {
        queue(OTHER_CONVERSATION, "남의 글");

        List<ChatPendingMessage> rows =
                transactions.execute(status -> pendingMessages.findByConversationIdOrderByIdAsc(CONVERSATION));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("멈춘 대화는 보낼 대화에서 빠지고 멈춤을 내리면 다시 들어간다")
    void excludesHeldConversationFromReadyToSendUntilReleased() {
        queue(CONVERSATION, "첫째");
        queue(CONVERSATION, "둘째");
        queue(OTHER_CONVERSATION, "남의 글");

        Integer held = transactions.execute(status -> pendingMessages.markHeld(CONVERSATION, true));

        assertThat(held).as("멈춤을 세운 행 수").isEqualTo(2);
        assertThat(readyToSend()).as("멈춘 뒤 보낼 대화").containsExactly(OTHER_CONVERSATION);

        Integer released = transactions.execute(status -> pendingMessages.markHeld(CONVERSATION, false));

        assertThat(released).as("멈춤을 내린 행 수").isEqualTo(2);
        assertThat(readyToSend()).as("멈춤을 내린 뒤 보낼 대화").containsExactlyInAnyOrder(CONVERSATION, OTHER_CONVERSATION);
    }

    @Test
    @DisplayName("한 행만 멈춰 있어도 그 대화는 보낼 대화에서 빠진다")
    void excludesConversationWhenOnlyOneRowIsHeld() {
        queue(CONVERSATION, "첫째");
        transactions.executeWithoutResult(
                status -> pendingMessages.save(ChatPendingMessage.queued(CONVERSATION, USER, "멈춘 글", true, NOW)));

        assertThat(readyToSend()).isEmpty();
    }

    @Test
    @DisplayName("다른 대화 번호로 취소하면 지우지 않고 0 을 돌려준다")
    void keepsRowWhenDeletingWithOtherConversation() {
        ChatPendingMessage row = queue(CONVERSATION, "첫째");

        Integer missed = transactions.execute(status -> pendingMessages.deleteOne(row.id(), OTHER_CONVERSATION));

        assertThat(missed).as("다른 대화 번호로 지운 행 수").isZero();
        assertThat(pendingMessages.findById(row.id())).as("남은 행").isPresent();

        Integer deleted = transactions.execute(status -> pendingMessages.deleteOne(row.id(), CONVERSATION));

        assertThat(deleted).as("제 대화 번호로 지운 행 수").isEqualTo(1);
        assertThat(pendingMessages.findById(row.id())).as("지운 뒤").isEmpty();
    }

    @Test
    @DisplayName("번호 목록으로 지우면 그 행만 지우고, 대화로 지우면 그 대화의 행만 지운다")
    void deletesByIdsAndByConversation() {
        ChatPendingMessage first = queue(CONVERSATION, "첫째");
        ChatPendingMessage second = queue(CONVERSATION, "둘째");
        queue(CONVERSATION, "셋째");
        queue(OTHER_CONVERSATION, "남의 글");

        Integer byIds =
                transactions.execute(status -> pendingMessages.deleteAllByIdIn(List.of(first.id(), second.id())));

        assertThat(byIds).as("번호 목록으로 지운 행 수").isEqualTo(2);
        assertThat(contentsOf(CONVERSATION)).containsExactly("셋째");

        Integer byConversation = transactions.execute(status -> pendingMessages.deleteAllOf(CONVERSATION));

        assertThat(byConversation).as("대화로 지운 행 수").isEqualTo(1);
        assertThat(contentsOf(CONVERSATION)).isEmpty();
        assertThat(contentsOf(OTHER_CONVERSATION)).containsExactly("남의 글");
    }

    @Test
    @DisplayName("두 글을 빈 줄 하나로 잇고 이은 길이가 그 글의 길이와 같다")
    void mergesContentsWithOneBlankLine() {
        List<ChatPendingMessage> rows = List.of(
                ChatPendingMessage.queued(CONVERSATION, USER, "첫째", false, NOW),
                ChatPendingMessage.queued(CONVERSATION, USER, "둘째", false, NOW));

        assertThat(ChatPendingMessage.merged(rows)).isEqualTo("첫째\n\n둘째");
        assertThat(ChatPendingMessage.mergedLength(rows, null)).isEqualTo("첫째\n\n둘째".length());
        assertThat(ChatPendingMessage.mergedLength(rows, "셋째")).isEqualTo("첫째\n\n둘째\n\n셋째".length());
    }

    @Test
    @DisplayName("쌓인 글이 없으면 이은 글은 비고 더하는 글의 길이만 센다")
    void countsOnlyAddedWhenNothingIsQueued() {
        assertThat(ChatPendingMessage.merged(List.of())).isEmpty();
        assertThat(ChatPendingMessage.mergedLength(List.of(), null)).isZero();
        assertThat(ChatPendingMessage.mergedLength(List.of(), "셋째")).isEqualTo("셋째".length());
    }

    private ChatPendingMessage queue(long conversationId, String content) {
        return transactions.execute(
                status -> pendingMessages.save(ChatPendingMessage.queued(conversationId, USER, content, false, NOW)));
    }

    private List<Long> readyToSend() {
        return transactions.execute(status -> pendingMessages.findConversationsReadyToSend());
    }

    private List<String> contentsOf(long conversationId) {
        return transactions.execute(status -> pendingMessages.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(ChatPendingMessage::content)
                .toList());
    }
}
