package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ConversationPublicIdLookup;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 대화 번호를 공개 식별자로 바꾸는 조회가 지운 대화를 두 port 에서 다르게 다루는 것을 고정한다. */
@SpringBootTest
@ActiveProfiles("test")
class ConversationPublicIdLookupTest {

    private static final long LOOKUP_USER_ID = 9204L;

    @Autowired
    ConversationPublicIdLookup lookup;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ConversationRepository conversations;

    private Conversation newConversation() {
        return conversations.save(Conversation.startedBy(LOOKUP_USER_ID, "", null, Instant.now()));
    }

    @Test
    @DisplayName("대화 둘을 저장하면 publicIdsOf 가 각 번호를 그 대화의 공개 식별자로 잇는다")
    void mapsConversationIdsToPublicIds() {
        Conversation first = newConversation();
        Conversation second = newConversation();

        assertThat(lookup.publicIdsOf(List.of(first.id(), second.id())))
                .containsEntry(first.id(), first.publicId())
                .containsEntry(second.id(), second.publicId())
                .hasSize(2);
    }

    @Test
    @DisplayName("하나를 지우면 publicIdsOf 는 둘 다 담고 activePublicIdsOf 는 지우지 않은 하나만 담는다")
    void activeLookupSkipsDeletedConversation() {
        Conversation kept = newConversation();
        Conversation deleted = newConversation();
        conversationWriter.deleteIfActive(deleted.id(), LOOKUP_USER_ID, Instant.now());
        List<Long> ids = List.of(kept.id(), deleted.id());

        assertThat(lookup.publicIdsOf(ids)).containsOnlyKeys(kept.id(), deleted.id());
        assertThat(lookup.activePublicIdsOf(ids))
                .containsOnlyKeys(kept.id())
                .containsEntry(kept.id(), kept.publicId());
    }

    @Test
    @DisplayName("번호 목록이 비면 두 조회 모두 빈 표를 돌려준다")
    void returnsEmptyMapForEmptyIds() {
        assertThat(lookup.publicIdsOf(List.of())).isEmpty();
        assertThat(lookup.activePublicIdsOf(List.of())).isEmpty();
    }
}
