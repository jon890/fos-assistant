package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.conversation.application.ConversationWriter;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 대화 제목이 저장할 때 암호화되고, 옮기거나 주인을 바꾼 암호문은 풀리지 않는지 확인한다(ADR-20261008 / data-encryption). */
@BackendIntegrationTest
class ConversationTitleEncryptionTest {

    private static final Instant NOW = Instant.parse("2026-11-10T06:00:00Z");
    private static final String TITLE = "평문-표식-7712 월급 이야기";
    private static final String FILLED = "평문-표식-7713 첫 메시지로 채운 제목";
    private static final String RENAMED = "평문-표식-7714 바꾼 이름";

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter writer;

    @Autowired
    ChatService chat;

    @Autowired
    AppUserRepository users;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppUser owner;


    @BeforeEach
    void setUp() {
        owner = user();
    }


    @AfterEach
    void tearDown() {
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("새 대화의 제목은 데이터베이스에 암호문으로 남고 읽으면 평문이다")
    void storesCiphertextAndReadsPlaintext() {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), TITLE, null, NOW));

        Map<String, Object> row = rowOf(saved.id());
        assertThat((String)row.get("TITLE")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("TITLE_KEY_ID")).isNotNull();
        assertThat(saved.title()).isEqualTo(TITLE);
        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEqualTo(TITLE);
    }


    @Test
    @DisplayName("첫 메시지로 채운 제목과 바꾼 이름이 암호문으로 남고 읽으면 평문이다")
    void filledAndRenamedTitlesAreCiphertext() {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), "", null, NOW));

        assertThat(writer.fillTitleIfBlank(saved, FILLED)).isEqualTo(1);

        assertThat((String)rowOf(saved.id()).get("TITLE")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEqualTo(FILLED);

        Conversation renamed = chat.rename(currentUser(), saved.id(), RENAMED);

        Map<String, Object> row = rowOf(saved.id());
        assertThat((String)row.get("TITLE")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("TITLE_KEY_ID")).isNotNull();
        assertThat(renamed.title()).isEqualTo(RENAMED);
        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEqualTo(RENAMED);
    }


    @Test
    @DisplayName("빈 제목은 평문 빈 글이고 key 칸이 비어 있으며 첫 메시지가 그 제목을 채운다")
    void blankTitleStaysPlainAndGetsFilled() {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), "", null, NOW));

        Map<String, Object> row = rowOf(saved.id());
        assertThat(row.get("TITLE")).isEqualTo("");
        assertThat(row.get("TITLE_KEY_ID")).isNull();
        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEmpty();

        writer.fillTitleIfBlank(saved, FILLED);

        assertThat(rowOf(saved.id()).get("TITLE_KEY_ID")).isNotNull();
        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEqualTo(FILLED);
        // 이미 채운 제목은 두 번째 채움이 바꾸지 않는다.
        assertThat(writer.fillTitleIfBlank(saved, "다른 제목")).isZero();
        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEqualTo(FILLED);
    }


    @Test
    @DisplayName("두 대화의 제목 암호문을 맞바꾸면 둘 다 읽을 수 없는 제목이다")
    void swappedCiphertextBecomesUnreadable() {
        Conversation first = conversations.save(Conversation.startedBy(owner.id(), TITLE, null, NOW));
        Conversation second = conversations.save(Conversation.startedBy(owner.id(), FILLED, null, NOW));
        Map<String, Object> firstRow = rowOf(first.id());
        Map<String, Object> secondRow = rowOf(second.id());

        jdbc.update("UPDATE conversation SET title = ?, title_key_id = ? WHERE id = ?",
            secondRow.get("TITLE"), secondRow.get("TITLE_KEY_ID"), first.id());
        jdbc.update("UPDATE conversation SET title = ?, title_key_id = ? WHERE id = ?",
            firstRow.get("TITLE"), firstRow.get("TITLE_KEY_ID"), second.id());

        assertThat(conversations.findById(first.id()).orElseThrow().title()).isEqualTo(Conversation.UNREADABLE_TITLE);
        assertThat(conversations.findById(second.id()).orElseThrow().title()).isEqualTo(Conversation.UNREADABLE_TITLE);
    }


    @Test
    @DisplayName("대화 줄의 주인을 바꾸면 제목이 풀리지 않는다")
    void changedOwnerMakesTitleUnreadable() {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), TITLE, null, NOW));

        jdbc.update("UPDATE conversation SET user_id = ? WHERE id = ?", user().id(), saved.id());

        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEqualTo(Conversation.UNREADABLE_TITLE);
    }


    @Test
    @DisplayName("key 칸이 비어 있는 옛 줄은 평문으로 읽힌다")
    void legacyRowWithoutKeyReadsAsPlaintext() {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), TITLE, null, NOW));

        jdbc.update("UPDATE conversation SET title = ?, title_key_id = NULL WHERE id = ?", "옛 평문 제목", saved.id());

        assertThat(conversations.findById(saved.id()).orElseThrow().title()).isEqualTo("옛 평문 제목");
    }


    @Test
    @DisplayName("대화 정리가 제목과 key 칸을 함께 비운다")
    void purgeClearsTitleAndKey() {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), TITLE, null, NOW));
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), saved.id());

        int marked = new TransactionTemplate(transactionManager)
            .execute(status -> conversations.markPurged(saved.id(), NOW));

        assertThat(marked).isEqualTo(1);
        Map<String, Object> row = rowOf(saved.id());
        assertThat(row.get("TITLE")).isEqualTo("");
        assertThat(row.get("TITLE_KEY_ID")).isNull();
    }


    private Map<String, Object> rowOf(Long conversationId) {
        return jdbc.queryForMap("SELECT title, title_key_id FROM conversation WHERE id = ?", conversationId);
    }


    private CurrentUser currentUser() {
        return new CurrentUser(owner.id(), owner.email(), "dad", 1L, UserRole.MEMBER);
    }


    private AppUser user() {
        String email = "title-" + UUID.randomUUID() + "@example.test";
        return users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
    }
}
