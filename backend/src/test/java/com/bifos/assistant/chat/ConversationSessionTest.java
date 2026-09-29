package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ConversationSessions;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 대화 turn 에 보낼 session 을 Control Plane 이 한 번만 정하고 대화 목록의 순서를 흔들지 않는 것을 고정한다. */
@SpringBootTest
@ActiveProfiles("test")
class ConversationSessionTest {

    @Autowired ConversationSessions sessions;
    @Autowired ConversationRepository conversations;
    @Autowired AppUserRepository users;

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
    void 새_대화는_fos_session을_정해_보낼_session과_뿌리_session에_함께_적는다() {
        Conversation conversation = newConversation();

        String sessionId = sessions.ensure(conversation);

        assertThat(sessionId).startsWith("fos-");
        assertThat(conversation.hermesSessionId()).isEqualTo(sessionId);
        assertThat(conversation.hermesRootSessionId()).isEqualTo(sessionId);
        Conversation stored = reload(conversation);
        assertThat(stored.hermesSessionId()).as("저장된 보낼 session").isEqualTo(sessionId);
        assertThat(stored.hermesRootSessionId()).as("저장된 뿌리 session").isEqualTo(sessionId);
    }

    @Test
    void 두_turn이_함께_정하려_하면_하나만_저장되고_진_쪽은_저장된_값을_다시_읽어_쓴다() {
        Conversation created = newConversation();
        // 두 turn 이 각자 session 이 빈 대화를 읽어 둔 상태다.
        Conversation winner = reload(created);
        Conversation loser = reload(created);

        String first = sessions.ensure(winner);
        String second = sessions.ensure(loser);

        assertThat(second).as("진 쪽이 돌려준 session").isEqualTo(first);
        assertThat(loser.hermesSessionId()).isEqualTo(first);
        assertThat(loser.hermesRootSessionId()).isEqualTo(first);
        Conversation stored = reload(created);
        assertThat(stored.hermesSessionId()).isEqualTo(first);
        assertThat(stored.hermesRootSessionId()).isEqualTo(first);
    }

    @Test
    void session을_정해도_대화의_updated_at은_바뀌지_않는다() {
        Conversation conversation = newConversation();
        Instant before = reload(conversation).updatedAt();

        sessions.ensure(conversation);

        assertThat(reload(conversation).updatedAt()).isEqualTo(before);
    }

    @Test
    void Hermes가_정한_session이_있는_옛_대화는_그대로_쓰고_뿌리를_채우지_않는다() {
        Conversation conversation = newConversation();
        conversations.touchSession(conversation.id(), "legacy-session", Instant.now());
        Conversation legacy = reload(conversation);

        String sessionId = sessions.ensure(legacy);

        assertThat(sessionId).isEqualTo("legacy-session");
        assertThat(legacy.executionSessionId()).isEqualTo("legacy-session");
        assertThat(reload(conversation).hermesRootSessionId()).isNull();
    }
}
