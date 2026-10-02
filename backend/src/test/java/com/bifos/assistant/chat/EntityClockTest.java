package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.mcp.domain.AgentToken;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 엔티티가 시각을 스스로 읽지 않고 부르는 쪽이 넘긴 값을 쓰는지 본다. */
class EntityClockTest {

    private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-01-02T00:00:00Z");

    @Test
    @DisplayName("대화를 고정 시각으로 만들면 createdAt 과 updatedAt 이 그 시각이다")
    void conversationUsesGivenInstantWhenCreated() {
        Conversation conversation = Conversation.startedBy(1L, "title", 2L, FIRST);

        assertThat(ReflectionTestUtils.getField(conversation, "createdAt")).isEqualTo(FIRST);
        assertThat(conversation.updatedAt()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("세션을 기억하면 updatedAt 이 넘긴 시각으로 바뀐다")
    void rememberSessionUsesGivenInstant() {
        Conversation conversation = Conversation.startedBy(1L, "title", 2L, FIRST);

        conversation.rememberSession("s", SECOND);

        assertThat(conversation.updatedAt()).isEqualTo(SECOND);
    }

    @Test
    @DisplayName("토큰을 두 번 폐기해도 폐기 시각은 처음 넘긴 값으로 남는다")
    void revokeKeepsFirstInstant() {
        AgentToken token = AgentToken.issueFor("profile", "hash", "label", FIRST);

        token.revoke(FIRST);
        token.revoke(SECOND);

        assertThat(token.revokedAt()).isEqualTo(FIRST);
    }
}
