package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.application.model.PersonAccess;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 관리자 목록에 사용자 발신 메시지의 마지막 시각만 들어가는지 확인한다. */
@BackendIntegrationTest
class PersonActivityTest {

    @Autowired
    PersonAccessService access;

    @Autowired
    SignInPolicy signIn;

    @Autowired
    TestClock clock;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConversationRepository conversations;

    @BeforeEach
    void setUp() {
        messages.deleteAll();
        conversations.deleteAll();
        people.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("사용자가 보낸 마지막 메시지만 활동 시각으로 모은다")
    void findsLastUserMessageBySenderRatherThanConversationOwner() {
        AllowedPerson allowed = people.save(AllowedPerson.of("activity@example.com", "사용자", "activity", Instant.EPOCH));
        AppUser user = users.save(AppUser.of("ACTIVITY@example.com", "사용자", 1L, UserRole.MEMBER, Instant.EPOCH));
        Instant first = Instant.parse("2026-10-08T01:00:00Z");
        Instant last = Instant.parse("2026-10-08T02:00:00Z");
        Conversation firstConversation = conversations.save(Conversation.startedBy(user.id(), "첫 대화", null, first));
        Conversation secondConversation = conversations.save(Conversation.startedBy(user.id(), "둘째 대화", null, last));
        messages.save(ChatMessage.fromUser(firstConversation.id(), user.id(), "first", first));
        messages.save(ChatMessage.fromAssistant(firstConversation.id(), "answer", null, last.plusSeconds(60)));
        messages.save(ChatMessage.fromSystem(firstConversation.id(), "notice", last.plusSeconds(120)));
        messages.save(ChatMessage.fromUser(secondConversation.id(), user.id(), "last", last));

        PersonAccess result = access.list().stream()
                .filter(entry -> entry.person().id().equals(allowed.id()))
                .findFirst()
                .orElseThrow();

        assertThat(result.joined()).isTrue();
        assertThat(result.lastConversationAt()).isEqualTo(last);
    }

    @Test
    @DisplayName("대화가 없는 아직 로그인하지 않은 사람은 활동 시각이 비어 있다")
    void returnsNullActivityForPersonWithoutUser() {
        AllowedPerson allowed = people.save(AllowedPerson.of("new@example.com", "새 사용자", "new-user", Instant.EPOCH));

        PersonAccess result = access.list().stream()
                .filter(entry -> entry.person().id().equals(allowed.id()))
                .findFirst()
                .orElseThrow();

        assertThat(result.joined()).isFalse();
        assertThat(result.lastConversationAt()).isNull();
    }

    @Test
    @DisplayName("관리자 변경에 오래된 허용 목록 행을 저장해도 로그인 시각은 유지한다")
    void preservesLoginTimeWhenAdminSavesStalePerson() {
        AllowedPerson person = people.save(AllowedPerson.of("stale@example.com", "사용자", "stale", Instant.EPOCH));
        AllowedPerson staleDisable = people.findById(person.id()).orElseThrow();
        Instant firstLogin = Instant.parse("2026-10-08T01:00:00Z");
        clock.set(firstLogin);

        assertThat(signIn.recordCompletion("stale@example.com")).isTrue();
        staleDisable.disable();
        people.save(staleDisable);
        assertThat(people.findById(person.id()).orElseThrow().lastLoginAt()).isEqualTo(firstLogin);

        AllowedPerson staleEnable = people.findById(person.id()).orElseThrow();
        AllowedPerson current = people.findById(person.id()).orElseThrow();
        current.enable();
        people.save(current);
        Instant secondLogin = firstLogin.plusSeconds(60);
        clock.set(secondLogin);
        assertThat(signIn.recordCompletion("stale@example.com")).isTrue();

        staleEnable.enable();
        people.save(staleEnable);
        assertThat(people.findById(person.id()).orElseThrow().lastLoginAt()).isEqualTo(secondLogin);
    }
}
