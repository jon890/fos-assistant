package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.people.application.PersonPurgePreview;
import com.bifos.assistant.people.application.dto.PurgePreview;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 사용자 정리 미리보기가 그 사용자의 줄만 세고 아무것도 바꾸지 않는지 확인한다. */
@BackendIntegrationTest
class PersonPurgePreviewTest {

    @Autowired
    PersonPurgePreview preview;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;


    @BeforeEach
    void setUp() {
        messages.deleteAll();
        conversations.deleteAll();
        people.deleteAll();
        users.deleteAll();
    }


    @Test
    @DisplayName("그 사용자의 대화만 세고 다른 사용자의 줄과 읽기 전후의 줄 수는 그대로다")
    void countsOnlyTheUsersRowsAndChangesNothing() {
        AllowedPerson target = people.save(AllowedPerson.of("target@example.com", "대상", "target"));
        AppUser targetUser = users.save(AppUser.of("TARGET@example.com", "대상", 1L, UserRole.MEMBER));
        AppUser other = users.save(AppUser.of("other@example.com", "다른 사람", 1L, UserRole.MEMBER));
        Instant at = Instant.parse("2026-11-01T01:00:00Z");
        conversations.save(Conversation.startedBy(targetUser.id(), "하나", null, at));
        conversations.save(Conversation.startedBy(targetUser.id(), "둘", null, at));
        conversations.save(Conversation.startedBy(other.id(), "남의 것", null, at));

        PurgePreview result = preview.preview(target.id());

        assertThat(result.items()).filteredOn(item -> item.table().equals("conversation"))
            .singleElement().satisfies(item -> assertThat(item.rows()).isEqualTo(2));
        assertThat(result.items()).filteredOn(item -> item.table().equals("chat_artifact"))
            .singleElement().satisfies(item -> assertThat(item.rows()).isZero());
        assertThat(conversations.count()).isEqualTo(3);
    }


    @Test
    @DisplayName("로그인한 적이 없는 사람은 빈 목록이고 없는 사람은 PERSON_NOT_FOUND 다")
    void emptyForPersonWithoutUserAndRejectsUnknownPerson() {
        AllowedPerson target = people.save(AllowedPerson.of("new@example.com", "새 사람", "new-person"));

        assertThat(preview.preview(target.id()).items()).isEmpty();
        assertThatThrownBy(() -> preview.preview(-1L)).isInstanceOf(ApiException.class);
    }
}
