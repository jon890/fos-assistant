package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 메시지 본문이 저장할 때 암호화되고, 옮기거나 주인을 바꾼 암호문은 풀리지 않는지 확인한다(ADR-20261008 / data-encryption). */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ChatMessageEncryptionTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private static final String QUESTION = "평문-표식-8812 이번 달 월급이 얼마 들어왔지";
    private static final String ANSWER = "평문-표식-8813 지난달과 같아요";

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppUser owner;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        owner = user();
        conversation = conversations.save(Conversation.startedBy(owner.id(), "월급 확인", null, NOW));
    }

    @AfterEach
    void tearDown() {
        dataKeys.forgetCachedKeys();
    }

    @Test
    @DisplayName("저장한 메시지는 데이터베이스에 암호문으로 남고 읽으면 평문이다")
    void storesCiphertextAndReadsPlaintext() {
        ChatMessage saved = messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));

        Map<String, Object> row = rowOf(saved.id());
        assertThat((String) row.get("CONTENT")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("CONTENT_KEY_ID")).isNotNull();
        assertThat(saved.content()).isEqualTo(QUESTION);
        assertThat(contentsOf(conversation.id())).containsExactly(QUESTION);
    }

    @Test
    @DisplayName("저장하는 네 길 모두 처음 넣는 줄에는 빈 본문만 쓰고 평문을 쓰지 않는다")
    void insertsEmptyContentBeforeSealingOnEveryWritePath() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 같은 연결로 읽으므로 아직 내보내지 않은 암호문 고침은 보이지 않고 insert 된 글만 보인다
            ChatMessage single = messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));
            assertThat(storedContentOf(single.id())).isEmpty();
            List<ChatMessage> batch = messages.saveAll(List.of(ChatMessage.fromSystem(conversation.id(), ANSWER, NOW)));
            assertThat(storedContentOf(batch.get(0).id())).isEmpty();

            ChatMessage flushed =
                    messages.saveAndFlush(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));
            List<ChatMessage> flushedBatch =
                    messages.saveAllAndFlush(List.of(ChatMessage.fromSystem(conversation.id(), ANSWER, NOW)));
            assertThat(storedContentOf(flushed.id())).startsWith("v1.");
            assertThat(storedContentOf(flushedBatch.get(0).id())).startsWith("v1.");
        });

        assertThat(jdbc.queryForList(
                        "SELECT content FROM chat_message WHERE conversation_id = ?", String.class, conversation.id()))
                .hasSize(4)
                .allSatisfy(content -> assertThat(content).startsWith("v1.").doesNotContain("평문-표식"));
    }

    @Test
    @DisplayName("두 메시지의 암호문을 맞바꾸면 둘 다 읽을 수 없는 메시지로 보이고 경고에는 식별자만 남는다")
    void swappedCiphertextBecomesUnreadable(CapturedOutput output) {
        ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));
        ChatMessage answer = messages.save(ChatMessage.fromSystem(conversation.id(), ANSWER, NOW));
        Map<String, Object> first = rowOf(question.id());
        Map<String, Object> second = rowOf(answer.id());

        jdbc.update("UPDATE chat_message SET content = ? WHERE id = ?", second.get("CONTENT"), question.id());
        jdbc.update("UPDATE chat_message SET content = ? WHERE id = ?", first.get("CONTENT"), answer.id());

        assertThat(contentsOf(conversation.id()))
                .containsExactly(ChatMessage.UNREADABLE_CONTENT, ChatMessage.UNREADABLE_CONTENT);
        assertThat(output.getAll())
                .contains("메시지 본문을 풀지 못했다 messageId=" + question.id())
                .doesNotContain("평문-표식");
    }

    @Test
    @DisplayName("대화의 주인을 데이터베이스에서 바꾸면 그 대화의 메시지가 풀리지 않는다")
    void changedOwnerMakesMessagesUnreadable() {
        messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));
        AppUser stranger = user();

        jdbc.update("UPDATE conversation SET user_id = ? WHERE id = ?", stranger.id(), conversation.id());

        assertThat(contentsOf(conversation.id())).containsExactly(ChatMessage.UNREADABLE_CONTENT);
    }

    @Test
    @DisplayName("암호화하기 전에 저장한 평문 줄은 그대로 읽힌다")
    void readsLegacyPlaintextRows() {
        ChatMessage saved = messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));

        jdbc.update("UPDATE chat_message SET content = ?, content_key_id = NULL WHERE id = ?", "옛 평문 질문", saved.id());

        assertThat(contentsOf(conversation.id())).containsExactly("옛 평문 질문");
    }

    @Test
    @DisplayName("데이터 key 를 지우면 그 사용자의 메시지는 읽을 수 없는 메시지로 보이고 조회는 실패하지 않는다")
    void destroyedDataKeyMakesMessagesUnreadable() {
        ChatMessage saved = messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));

        jdbc.update("DELETE FROM user_data_key WHERE id = ?", rowOf(saved.id()).get("CONTENT_KEY_ID"));
        dataKeys.forgetCachedKeys();

        assertThat(contentsOf(conversation.id())).containsExactly(ChatMessage.UNREADABLE_CONTENT);
    }

    @Test
    @DisplayName("같은 사용자의 여러 대화는 데이터 key 하나를 함께 쓴다")
    void sharesOneDataKeyAcrossConversationsOfSameUser() {
        Conversation other = conversations.save(Conversation.startedBy(owner.id(), "다른 대화", null, NOW));

        ChatMessage first = messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), QUESTION, NOW));
        ChatMessage second = messages.save(ChatMessage.fromUser(other.id(), owner.id(), ANSWER, NOW));

        assertThat(rowOf(first.id()).get("CONTENT_KEY_ID"))
                .isEqualTo(rowOf(second.id()).get("CONTENT_KEY_ID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_data_key WHERE user_id = ?", Long.class, owner.id()))
                .isEqualTo(1L);
    }

    private List<String> contentsOf(Long conversationId) {
        return messages.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(ChatMessage::content)
                .toList();
    }

    private String storedContentOf(Long messageId) {
        return jdbc.queryForObject("SELECT content FROM chat_message WHERE id = ?", String.class, messageId);
    }

    private Map<String, Object> rowOf(Long messageId) {
        return jdbc.queryForMap("SELECT content, content_key_id FROM chat_message WHERE id = ?", messageId);
    }

    private AppUser user() {
        String email = "cipher-" + UUID.randomUUID() + "@example.test";
        return users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
    }
}
