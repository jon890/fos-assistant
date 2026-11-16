package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.conversation.domain.PendingMessage;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 대기 줄 값의 steer 칸(말풍선 번호, 끼우는 중)을 본다(ADR-20261116 / run-steer). */
class PendingMessagesTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");


    @Test
    @DisplayName("돌아온 글은 받은 순서대로 줄 앞에 들어가고 번호는 줄의 가장 큰 번호보다 크다")
    void prependsCarriedInOrderWithLargerIds() {
        PendingMessages queue = PendingMessages.EMPTY.append("뒤 글", NOW);
        long existing = queue.items().getFirst().id();

        PendingMessages next = queue.prependCarried(
            List.of(new PendingMessages.Carried(10L, "첫 말풍선"), new PendingMessages.Carried(11L, "둘째 말풍선")), NOW);

        assertThat(next.items()).extracting(PendingMessage::content).containsExactly("첫 말풍선", "둘째 말풍선", "뒤 글");
        assertThat(next.items().get(0).id()).isGreaterThan(existing);
        assertThat(next.items().get(1).id()).isGreaterThan(next.items().get(0).id());
        assertThat(next.carriedMessageIds()).containsExactly(10L, 11L);
        PendingMessages appended = next.append("새 글", NOW);
        assertThat(appended.items().getLast().id()).isGreaterThan(next.items().get(1).id());
    }


    @Test
    @DisplayName("말풍선이 있는 글은 상한과 질문 글에서 빠지고 Hermes 에 보낼 글에는 들어간다")
    void carriedItemsAreNotCountedButMerged() {
        PendingMessages queue = PendingMessages.EMPTY.append("새 글", NOW)
            .prependCarried(List.of(new PendingMessages.Carried(10L, "돌아온 글")), NOW);

        assertThat(queue.countable()).isEqualTo(1);
        assertThat(queue.questionText()).isEqualTo("새 글");
        assertThat(queue.merged()).isEqualTo("돌아온 글\n\n새 글");
        assertThat(queue.mergedLength("더")).isEqualTo("새 글\n\n더".length());

        PendingMessages onlyCarried = PendingMessages.EMPTY.prependCarried(
            List.of(new PendingMessages.Carried(10L, "돌아온 글")), NOW);
        assertThat(onlyCarried.questionText()).isEmpty();
        assertThat(onlyCarried.mergedLength("더")).isEqualTo(1);
    }


    @Test
    @DisplayName("끼우는 중인 글이 있으면 다음 turn 을 붙잡고 표시를 지우면 풀린다")
    void steeringBlocksNextTurnUntilCleared() {
        PendingMessages queue = PendingMessages.EMPTY.append("글", NOW);
        Long id = queue.items().getFirst().id();

        PendingMessages steering = queue.withSteering(id, true);

        assertThat(steering.blockedBySteering()).isTrue();
        assertThat(steering.withSteering(id, false).blockedBySteering()).isFalse();
        assertThat(queue.blockedBySteering()).isFalse();
    }
}
