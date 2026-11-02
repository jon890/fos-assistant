package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.domain.PendingMessage;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화의 대기 줄({@code conversation.pending_messages})을 읽고 바꾼다(ADR-048).
 *
 * <p>JSON 은 {@code {"version":1,"held":false,"items":[{"id":1,"content":"글","createdAt":"ISO-8601"}]}} 이다. 빈 줄은 칸을
 * 비워 저장한다. 바꾸는 일은 모두 {@link #change} 를 지나고, 대화 줄을 잠근 뒤 읽고 쓴다. 그래서 더하기, 취소, 멈춤, 보낸 글
 * 빼기가 같은 대화에서 차례로 돌고 서버가 여럿이어도 서로의 변경을 덮지 않는다.
 *
 * <p>부르는 쪽의 트랜잭션이 있으면 그 안에서 바꾼다. 보낸 글을 빼는 것과 사용자 메시지 저장이 함께 남거나 함께 빠진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingMessageStore {

    static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConversationRepository conversations;


    /** 잠그지 않고 읽는다. 보일 값과 보낼지 미리 보는 데 쓴다. 바꾸기 전에는 {@link #change} 가 다시 읽는다. */
    @Transactional(readOnly = true)
    public PendingMessages read(Long conversationId) {
        return conversations.findPendingMessages(conversationId).map(PendingMessageStore::decode).orElse(PendingMessages.EMPTY);
    }


    /**
     * 대화 줄을 잠그고 대기 줄을 바꾼다. 바뀐 값을 돌려준다.
     *
     * <p>{@code change} 가 예외를 던지면 아무것도 바꾸지 않는다. 대화가 없으면 빈 줄에서 시작하고 저장할 줄이 없어 쓰지 않는다.
     */
    @Transactional
    public PendingMessages change(Long conversationId, UnaryOperator<PendingMessages> change) {
        PendingMessages current = conversations.lockPendingMessages(conversationId)
            .map(PendingMessageStore::decode)
            .orElse(PendingMessages.EMPTY);
        PendingMessages next = change.apply(current);
        if (!next.equals(current)) {
            conversations.writePendingMessages(conversationId, encode(next));
        }
        return next;
    }


    /** 대화의 대기 줄을 모두 멈추거나 푼다. */
    @Transactional
    public PendingMessages hold(Long conversationId, boolean held) {
        return change(conversationId, queue -> queue.withHeld(held));
    }


    /**
     * {@code ids} 의 글이 하나라도 남아 있으면 대기 줄을 멈춘다. 남았는지 보는 것과 멈추는 것이 한 트랜잭션이다.
     *
     * @return 남은 글이 있어 멈췄다
     */
    @Transactional
    public boolean holdIfAnyLeft(Long conversationId, Collection<Long> ids) {
        return change(conversationId, queue -> queue.containsAny(ids) ? queue.withHeld(true) : queue).containsAny(ids);
    }


    /** 대기 줄을 비운다. 대화를 지우거나 정리할 때 부른다. 깨진 값이어도 읽지 않고 비운다. */
    @Transactional
    public void clear(Long conversationId) {
        conversations.lockPendingMessages(conversationId);
        conversations.writePendingMessages(conversationId, null);
    }


    /**
     * 보낼 수 있는 대기 줄을 가진 대화의 번호다. 멈춘 줄은 뺀다.
     *
     * <p>깨진 값은 경고를 남기고 뺀다. 그 대화 하나 때문에 기동 뒤 다른 대화를 깨우지 못하면 안 된다.
     */
    @Transactional(readOnly = true)
    public List<Long> conversationsReadyToSend() {
        return conversations.findIdsWithPendingMessages().stream().filter(this::readyToSend).toList();
    }


    private boolean readyToSend(Long conversationId) {
        try {
            return read(conversationId).sendable();
        } catch (IllegalStateException ex) {
            log.warn("대기 줄을 읽지 못해 보내지 않는다 conversationId={}", conversationId, ex);
            return false;
        }
    }


    /** 빈 줄은 null 이다. */
    public static String encode(PendingMessages queue) {
        if (queue.isEmpty()) {
            return null;
        }
        List<StoredItem> items = queue.items().stream()
            .map(item -> new StoredItem(item.id(), item.content(), item.createdAt().toString()))
            .toList();
        return JSON.writeValueAsString(new Stored(VERSION, queue.held(), items));
    }


    /**
     * 저장된 JSON 을 대기 줄로 읽는다. 마이그레이션 검사가 옮긴 JSON 을 같은 해석으로 읽으려고 연다.
     *
     * <p>깨진 값을 빈 줄로 보지 않는다. 빈 줄로 보면 사용자가 멈춰 둔 글이 사라진 것처럼 보이고, 다음 저장이 그 글을 지운다.
     *
     * @throws IllegalStateException 모르는 버전이거나 형식이 깨졌을 때
     */
    public static PendingMessages decode(String value) {
        Stored stored;
        try {
            stored = JSON.readValue(value, Stored.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("stored pending messages are not readable", ex);
        }
        if (stored == null) {
            throw new IllegalStateException("stored pending messages are empty");
        }
        if (stored.version() == null || stored.version() != VERSION) {
            throw new IllegalStateException("stored pending messages have an unknown version");
        }
        if (stored.held() == null || stored.items() == null || stored.items().isEmpty()
            || stored.items().stream().anyMatch(StoredItem::incomplete)) {
            throw new IllegalStateException("stored pending messages are incomplete");
        }
        try {
            List<PendingMessage> items = stored.items().stream()
                .map(item -> new PendingMessage(item.id(), item.content(), Instant.parse(item.createdAt())))
                .toList();
            return new PendingMessages(stored.held(), items);
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException("stored pending messages have an unreadable time", ex);
        }
    }


    /** 저장하는 JSON 의 모양이다. 칸 이름이 저장되므로 바꾸려면 버전을 올린다. */
    private record Stored(Integer version, Boolean held, List<StoredItem> items) {
    }

    private record StoredItem(Long id, String content, String createdAt) {

        boolean incomplete() {
            return id == null || content == null || createdAt == null;
        }
    }
}
