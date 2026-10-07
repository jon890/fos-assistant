package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;

/**
 * 대화의 조건부 update 가 트랜잭션을 {@link ConversationWriter} 에서 얻는 것을 고정한다.
 *
 * <p>이 클래스의 검사는 트랜잭션 밖에서 돈다. 오래 도는 turn 이 이 update 를 그렇게 부르기 때문이다.
 */
@BackendIntegrationTest
class ConversationWriterTest {

    private static final long WRITER_USER_ID = 9203L;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ConversationRepository conversations;

    /**
     * 사용자를 만들지 않고 고정 번호로 제목이 빈 대화만 만든다.
     *
     * <p>사용자를 저장하고 남기면 그룹의 첫 사용자 판정이 다른 검사에서 달라진다. 검사는 대화 번호로만
     * 읽고 고치므로 같은 사용자 번호의 대화가 여럿이어도 서로 흔들리지 않는다.
     */
    private Conversation newUntitledConversation() {
        return conversations.save(Conversation.startedBy(WRITER_USER_ID, "", null, Instant.now()));
    }

    private String storedTitle(Conversation conversation) {
        return conversations.findById(conversation.id()).orElseThrow().title();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 Writer 로 빈 제목을 채우면 1 을 돌려주고 제목이 저장된다")
    void fillsBlankTitleOutsideTransactionThroughWriter() {
        Conversation conversation = newUntitledConversation();

        int updated = conversationWriter.fillTitleIfBlank(conversation.id(), "새 제목");

        assertThat(updated).isEqualTo(1);
        assertThat(storedTitle(conversation)).isEqualTo("새 제목");
    }

    @Test
    @DisplayName("이미 제목이 있으면 Writer 가 0 을 돌려주고 제목을 바꾸지 않는다")
    void keepsExistingTitleAndReturnsZero() {
        Conversation conversation = newUntitledConversation();
        conversationWriter.fillTitleIfBlank(conversation.id(), "먼저 채운 제목");

        int updated = conversationWriter.fillTitleIfBlank(conversation.id(), "나중 제목");

        assertThat(updated).isZero();
        assertThat(storedTitle(conversation)).isEqualTo("먼저 채운 제목");
    }

    @Test
    @DisplayName("트랜잭션 밖에서 저장소의 update 를 직접 부르면 InvalidDataAccessApiUsageException 이 나고 제목은 그대로다")
    void rejectsDirectRepositoryUpdateOutsideTransaction() {
        Conversation conversation = newUntitledConversation();

        assertThatThrownBy(() -> conversations.fillTitleIfBlank(conversation.id(), "새 제목"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);

        assertThat(storedTitle(conversation)).isEmpty();
    }
}
