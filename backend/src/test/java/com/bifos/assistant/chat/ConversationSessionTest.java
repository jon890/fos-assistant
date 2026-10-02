package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ConversationSessions;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.orchestration.domain.RunSession;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 대화 turn 에 보낼 session 을 Control Plane 이 한 번만 정하고 대화 목록의 순서를 흔들지 않는 것을 고정한다. */
@SpringBootTest
@ActiveProfiles("test")
class ConversationSessionTest {

    @Autowired
    ConversationSessions sessions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    /** 다른 검사 클래스와 겹치지 않도록 매번 새 사용자로 대화를 만든다. */
    private Conversation newConversation() {
        String email = "session-" + System.nanoTime() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return conversations.save(Conversation.startedBy(user.id(), "제목", null));
    }

    private Conversation reload(Conversation conversation) {
        return conversations.findById(conversation.id()).orElseThrow();
    }

    @Test
    @DisplayName("새 대화는 fos session을 정해 보낼 session과 루트 session에 함께 적는다")
    void newConversationFixesSessionAndRecordsItForSendAndRootSession() {
        Conversation conversation = newConversation();

        RunSession session = sessions.ensure(conversation);

        String sessionId = session.runtimeSessionId();
        assertThat(sessionId).startsWith("fos-");
        assertThat(session.correlationSessionId()).isEqualTo(sessionId);
        assertThat(conversation.hermesSessionId()).isEqualTo(sessionId);
        assertThat(conversation.hermesRootSessionId()).isEqualTo(sessionId);
        Conversation stored = reload(conversation);
        assertThat(stored.hermesSessionId()).as("저장된 보낼 session").isEqualTo(sessionId);
        assertThat(stored.hermesRootSessionId()).as("저장된 루트 session").isEqualTo(sessionId);
    }

    @Test
    @DisplayName("두 turn이 함께 정하려 하면 하나만 저장되고 진 쪽은 저장된 값을 다시 읽어 쓴다")
    void concurrentTurnsSaveOnlyOneAndLoserRereadsStoredValue() {
        Conversation created = newConversation();
        // 두 turn 이 각자 session 이 빈 대화를 읽어 둔 상태다.
        Conversation winner = reload(created);
        Conversation loser = reload(created);

        RunSession first = sessions.ensure(winner);
        RunSession second = sessions.ensure(loser);

        assertThat(first.runtimeSessionId()).startsWith("fos-");
        assertThat(first.correlationSessionId()).isEqualTo(first.runtimeSessionId());
        assertThat(second).as("진 쪽이 돌려준 session").isEqualTo(first);
        assertThat(loser.hermesSessionId()).isEqualTo(first.runtimeSessionId());
        assertThat(loser.hermesRootSessionId()).isEqualTo(first.correlationSessionId());
        Conversation stored = reload(created);
        assertThat(stored.hermesSessionId()).isEqualTo(first.runtimeSessionId());
        assertThat(stored.hermesRootSessionId()).isEqualTo(first.correlationSessionId());
    }

    @Test
    @DisplayName("session을 정해도 대화의 updated at은 바뀌지 않는다")
    void fixingSessionLeavesConversationUpdatedAtUnchanged() {
        Conversation conversation = newConversation();
        Instant before = reload(conversation).updatedAt();

        sessions.ensure(conversation);

        assertThat(reload(conversation).updatedAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("Hermes가 정한 session이 있는 옛 대화는 그대로 쓰고 루트를 채우지 않는다")
    void keepsOldConversationWithHermesSessionAndDoesNotFillRoot() {
        Conversation conversation = newConversation();
        conversations.touchSession(conversation.id(), "legacy-session", Instant.now());
        Conversation legacy = reload(conversation);

        RunSession session = sessions.ensure(legacy);

        assertThat(session.runtimeSessionId()).isEqualTo("legacy-session");
        assertThat(session.correlationSessionId()).isEqualTo("legacy-session");
        assertThat(reload(conversation).hermesRootSessionId()).isNull();
    }
}
