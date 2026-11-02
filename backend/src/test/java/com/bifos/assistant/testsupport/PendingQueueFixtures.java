package com.bifos.assistant.testsupport;

import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 검사가 대화의 대기 줄({@code conversation.pending_messages})을 채우고 읽는 도구다(ADR-048).
 *
 * <p>멈춤은 줄 하나에 하나다. {@link Row} 는 옛 검사가 행마다 보던 멈춤을 그 줄의 값으로 함께 싣는다.
 */
public final class PendingQueueFixtures {

    private PendingQueueFixtures() {
    }


    /** 글 하나를 더한다. {@code held} 가 참이면 그 대화의 대기 줄 전체를 멈춘다. */
    public static void queue(PendingMessageStore store, Long conversationId, String text, boolean held, Instant at) {
        store.change(conversationId, queue -> {
            PendingMessages next = queue.append(text, at);
            return held ? next.withHeld(true) : next;
        });
    }


    public static List<Row> rows(PendingMessageStore store, Long conversationId) {
        PendingMessages queue = store.read(conversationId);
        return queue.items().stream().map(item -> new Row(item.id(), item.content(), queue.held())).toList();
    }


    /** 모든 대화의 대기 줄을 비운다. H2 를 함께 쓰는 다른 검사가 남긴 줄이 기동 뒤 보내기에 섞이지 않게 한다. */
    public static void clearAll(JdbcTemplate jdbc) {
        jdbc.update("UPDATE conversation SET pending_messages = NULL WHERE pending_messages IS NOT NULL");
    }


    /** 대기 글 하나와 그 줄의 멈춤이다. */
    public record Row(Long id, String content, boolean held) {
    }
}
