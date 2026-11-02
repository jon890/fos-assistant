package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.PendingMessage;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import com.bifos.assistant.chat.conversation.domain.UnreadablePendingMessagesException;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대화 줄의 대기 줄 칸({@code conversation.pending_messages})을 읽고 바꾸는 저장소와 대기 줄 값을 본다(ADR-048).
 *
 * <p>검사들이 H2 를 함께 써서 만든 대화를 앞뒤로 지운다.
 */
@BackendIntegrationTest
class PendingMessageStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private static final long USER = 7L;

    @Autowired
    PendingMessageStore pendingMessages;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    JdbcTemplate jdbc;

    private Long conversation;
    private Long otherConversation;


    @BeforeEach
    void setUp() {
        conversation = conversations.save(Conversation.startedBy(USER, "대기 줄", null, NOW)).id();
        otherConversation = conversations.save(Conversation.startedBy(USER, "남의 대기 줄", null, NOW)).id();
    }


    @AfterEach
    void tearDown() {
        conversations.deleteAllById(List.of(conversation, otherConversation));
    }


    @Test
    @DisplayName("대화의 대기 글만 넣은 순서로 읽고 저장한 JSON 을 그대로 다시 읽는다")
    void readsOnlyItemsOfConversationInQueuedOrder() {
        queue(conversation, "첫째");
        queue(otherConversation, "남의 글");
        queue(conversation, "둘째");

        PendingMessages queue = pendingMessages.read(conversation);

        assertThat(queue.items()).extracting(PendingMessage::content).containsExactly("첫째", "둘째");
        assertThat(queue.items()).extracting(PendingMessage::createdAt).containsOnly(NOW);
        assertThat(queue.held()).isFalse();
        String stored = jdbc.queryForObject(
            "SELECT pending_messages FROM conversation WHERE id = ?", String.class, conversation);
        assertThat(PendingMessageStore.decode(stored)).isEqualTo(queue);
    }


    @Test
    @DisplayName("대기 글이 없는 대화는 빈 줄을 주고 칸이 비어 있다")
    void readsEmptyQueueForConversationWithoutItems() {
        queue(otherConversation, "남의 글");

        assertThat(pendingMessages.read(conversation)).isEqualTo(PendingMessages.EMPTY);
        assertThat(storedOf(conversation)).isNull();
    }


    @Test
    @DisplayName("멈춘 대화는 보낼 대화에서 빠지고 멈춤을 내리면 다시 들어간다")
    void excludesHeldConversationFromReadyToSendUntilReleased() {
        queue(conversation, "첫째");
        queue(conversation, "둘째");
        queue(otherConversation, "남의 글");

        pendingMessages.hold(conversation, true);

        assertThat(pendingMessages.read(conversation).held()).isTrue();
        assertThat(readyToSend()).as("멈춘 뒤 보낼 대화").containsExactly(otherConversation);

        pendingMessages.hold(conversation, false);

        assertThat(readyToSend()).as("멈춤을 내린 뒤 보낼 대화").containsExactly(conversation, otherConversation);
    }


    @Test
    @DisplayName("멈춘 줄에 더한 글도 멈춘 채로 들어간다")
    void appendsToHeldQueueAsHeld() {
        queue(conversation, "첫째");
        pendingMessages.hold(conversation, true);

        queue(conversation, "둘째");

        assertThat(pendingMessages.read(conversation).held()).isTrue();
        assertThat(readyToSend()).doesNotContain(conversation);
    }


    @Test
    @DisplayName("글을 모두 빼면 칸을 비우고 멈춤도 사라진다")
    void clearsColumnAndHoldWhenLastItemIsRemoved() {
        queue(conversation, "첫째");
        pendingMessages.hold(conversation, true);
        List<Long> ids = pendingMessages.read(conversation).ids();

        pendingMessages.change(conversation, queue -> queue.without(ids));

        assertThat(storedOf(conversation)).isNull();
        queue(conversation, "새 글");
        assertThat(pendingMessages.read(conversation).held()).as("비운 뒤 새로 더한 글").isFalse();
    }


    @Test
    @DisplayName("바꾸다 예외가 나면 저장한 줄을 그대로 둔다")
    void keepsQueueWhenChangeThrows() {
        queue(conversation, "첫째");

        assertThatThrownBy(() -> pendingMessages.change(conversation, queue -> {
            throw new IllegalStateException("바꾸지 않는다");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(pendingMessages.read(conversation).items()).extracting(PendingMessage::content).containsExactly("첫째");
    }


    @Test
    @DisplayName("대기 줄을 비우면 그 대화의 칸만 비운다")
    void clearsOnlyThatConversation() {
        queue(conversation, "첫째");
        queue(otherConversation, "남의 글");

        pendingMessages.clear(conversation);

        assertThat(storedOf(conversation)).isNull();
        assertThat(pendingMessages.read(otherConversation).items()).extracting(PendingMessage::content)
            .containsExactly("남의 글");
    }


    @Test
    @DisplayName("깨진 칸은 빈 줄로 보지 않고 오류로 올리며 보낼 대화에서는 빠진다")
    void refusesBrokenColumn() {
        jdbc.update("UPDATE conversation SET pending_messages = ? WHERE id = ?", "{\"version\":2}", conversation);
        queue(otherConversation, "남의 글");

        assertThatThrownBy(() -> pendingMessages.read(conversation)).isInstanceOf(UnreadablePendingMessagesException.class);
        assertThat(readyToSend()).containsExactly(otherConversation);
    }


    @Test
    @DisplayName("읽어 둔 대화를 고쳐 저장해도 그 사이 바뀐 대기 줄을 덮지 않는다")
    void entitySaveKeepsQueueChangedMeanwhile() {
        Conversation loaded = conversations.findById(conversation).orElseThrow();
        queue(conversation, "첫째");

        loaded.assignNewSession("session-1");
        conversations.saveAndFlush(loaded);

        assertThat(pendingMessages.read(conversation).items()).extracting(PendingMessage::content).containsExactly("첫째");
    }


    @Test
    @DisplayName("보낼 글이 모두 빠졌으면 멈추지 않고 남은 글이 있으면 멈춘다")
    void holdsOnlyWhenSentItemIsLeft() {
        queue(conversation, "첫째");
        List<Long> sent = pendingMessages.read(conversation).ids();
        pendingMessages.change(conversation, queue -> queue.without(sent).append("새 글", NOW.plusMillis(1)));

        assertThat(pendingMessages.holdIfAnyLeft(conversation, sent)).isFalse();
        assertThat(pendingMessages.read(conversation).held()).isFalse();

        List<Long> left = pendingMessages.read(conversation).ids();
        assertThat(pendingMessages.holdIfAnyLeft(conversation, left)).isTrue();
        assertThat(pendingMessages.read(conversation).held()).isTrue();
    }


    @Test
    @DisplayName("두 글을 빈 줄 하나로 잇고 이은 길이가 그 글의 길이와 같다")
    void mergesContentsWithOneBlankLine() {
        PendingMessages queue = PendingMessages.EMPTY.append("첫째", NOW).append("둘째", NOW);

        assertThat(queue.merged()).isEqualTo("첫째\n\n둘째");
        assertThat(queue.mergedLength(null)).isEqualTo("첫째\n\n둘째".length());
        assertThat(queue.mergedLength("셋째")).isEqualTo("첫째\n\n둘째\n\n셋째".length());
    }


    @Test
    @DisplayName("쌓인 글이 없으면 이은 글은 비고 더하는 글의 길이만 센다")
    void countsOnlyAddedWhenNothingIsQueued() {
        assertThat(PendingMessages.EMPTY.merged()).isEmpty();
        assertThat(PendingMessages.EMPTY.mergedLength(null)).isZero();
        assertThat(PendingMessages.EMPTY.mergedLength("셋째")).isEqualTo("셋째".length());
    }


    @Test
    @DisplayName("새 글의 번호는 같은 시각에도 커지고 비운 뒤 더한 글은 앞서 보낸 글의 번호를 받지 않는다")
    void numbersNeverRepeatAfterQueueIsEmptied() {
        PendingMessages queue = PendingMessages.EMPTY.append("첫째", NOW).append("둘째", NOW);
        List<Long> sent = queue.ids();

        PendingMessages later = queue.without(sent).append("셋째", NOW.plusMillis(1));

        assertThat(sent.get(1)).isGreaterThan(sent.get(0));
        assertThat(later.ids()).doesNotContainAnyElementsOf(sent);
    }


    private void queue(Long conversationId, String content) {
        pendingMessages.change(conversationId, queue -> queue.append(content, NOW));
    }


    private List<Long> readyToSend() {
        return pendingMessages.conversationsReadyToSend().stream()
            .filter(id -> id.equals(conversation) || id.equals(otherConversation))
            .toList();
    }


    private String storedOf(Long conversationId) {
        return jdbc.queryForObject("SELECT pending_messages FROM conversation WHERE id = ?", String.class, conversationId);
    }
}
