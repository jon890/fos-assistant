package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.conversation.application.ChatPlaintextBackfill;
import com.bifos.assistant.chat.conversation.application.ChatPlaintextSealer;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.application.dto.BackfillDirection;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/** 키를 넣기 전에 저장한 메시지와 대화 제목을 조금씩 암호화하고, 되돌릴 때 평문으로 되돌리는지 확인한다(ADR-20261008 / data-encryption). */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ChatPlaintextBackfillTest {

    private static final Instant NOW = Instant.parse("2026-11-10T06:00:00Z");
    private static final int ALL = 100_000;

    @Autowired
    ChatPlaintextBackfill backfill;

    @Autowired
    ChatPlaintextSealer sealer;

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
        // 같은 데이터베이스를 쓰는 다른 검사가 남긴 평문 줄이 배치 크기를 먹지 않게 먼저 비운다
        backfill.run(BackfillDirection.SEAL, ALL);
        owner = user();
        conversation = conversations.save(Conversation.startedBy(owner.id(), "월급 확인", null, NOW));
    }


    @AfterEach
    void tearDown() {
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("평문 메시지 세 줄을 배치 크기 2 로 돌리면 두 줄만 암호화되고 다시 돌리면 나머지가 암호화된다")
    void sealsOnlyBatchSizeRowsPerRun(CapturedOutput output) {
        List<Long> ids = plainMessages("평문-표식-1101 하나", "평문-표식-1102 둘", "평문-표식-1103 셋");

        assertThat(backfill.run(BackfillDirection.SEAL, 2)).isEqualTo(2);

        assertThat(sealedCount(ids)).isEqualTo(2);
        assertThat(rowOf(ids.get(0)).get("CONTENT_KEY_ID")).isNotNull();
        assertThat(rowOf(ids.get(1)).get("CONTENT_KEY_ID")).isNotNull();
        assertThat(rowOf(ids.get(2)).get("CONTENT_KEY_ID")).isNull();

        assertThat(backfill.run(BackfillDirection.SEAL, 2)).isEqualTo(1);

        assertThat(sealedCount(ids)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT content FROM chat_message WHERE conversation_id = ?", String.class, conversation.id()))
            .allSatisfy(content -> assertThat(content).startsWith("v1.").doesNotContain("평문-표식"));
        assertThat(contentsOf(conversation.id()))
            .containsExactly("평문-표식-1101 하나", "평문-표식-1102 둘", "평문-표식-1103 셋");
        assertThat(output.getAll()).contains("column=chat_message.content").doesNotContain("평문-표식");

        assertThat(backfill.run(BackfillDirection.SEAL, 2)).isZero();
    }


    @Test
    @DisplayName("지웠지만 아직 정리하지 않은 대화의 평문 줄도 암호화된다")
    void deletedButNotPurgedConversationIsSealed() {
        List<Long> ids = plainMessages("평문-표식-1111 지운 대화");
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), conversation.id());

        backfill.run(BackfillDirection.SEAL, ALL);

        assertThat(sealedCount(ids)).isEqualTo(1);
    }


    @Test
    @DisplayName("평문 제목은 암호화되고 빈 제목은 평문 빈 글로 남는다")
    void sealsPlainTitlesAndLeavesBlankOnes() {
        Conversation blank = conversations.save(Conversation.startedBy(owner.id(), "", null, NOW));
        jdbc.update("UPDATE conversation SET title = ?, title_key_id = NULL WHERE id = ?", "평문-표식-1121 제목", conversation.id());

        backfill.run(BackfillDirection.SEAL, ALL);

        Map<String, Object> row = titleRowOf(conversation.id());
        assertThat((String)row.get("TITLE")).startsWith("v1.").doesNotContain("평문-표식");
        assertThat(row.get("TITLE_KEY_ID")).isNotNull();
        assertThat(conversations.findById(conversation.id()).orElseThrow().title()).isEqualTo("평문-표식-1121 제목");
        Map<String, Object> blankRow = titleRowOf(blank.id());
        assertThat(blankRow.get("TITLE")).isEqualTo("");
        assertThat(blankRow.get("TITLE_KEY_ID")).isNull();
    }


    @Test
    @DisplayName("빈 본문의 메시지는 대상이 아니라 그대로 남는다")
    void emptyContentIsLeftAlone() {
        List<Long> ids = plainMessages("");

        backfill.run(BackfillDirection.SEAL, ALL);

        assertThat(rowOf(ids.get(0)).get("CONTENT")).isEqualTo("");
        assertThat(rowOf(ids.get(0)).get("CONTENT_KEY_ID")).isNull();
    }


    @Test
    @DisplayName("OFF 이면 평문 줄을 건드리지 않는다")
    void offDoesNothing() {
        List<Long> ids = plainMessages("평문-표식-1131 그대로");

        assertThat(backfill.run(BackfillDirection.OFF, ALL)).isZero();

        assertThat(rowOf(ids.get(0)).get("CONTENT")).isEqualTo("평문-표식-1131 그대로");
    }


    @Test
    @DisplayName("UNSEAL 이면 암호문 줄이 평문과 빈 key 칸으로 돌아가고 데이터 key 가 없는 줄은 건너뛴다")
    void unsealRestoresPlaintextAndSkipsUnreadableRows(CapturedOutput output) {
        List<Long> ids = plainMessages("평문-표식-1141 하나", "평문-표식-1142 둘");
        AppUser stranger = user();
        Conversation other = conversations.save(Conversation.startedBy(stranger.id(), "남의 대화", null, NOW));
        Long lost = messages.save(ChatMessage.fromUser(other.id(), stranger.id(), "평문-표식-1143 잃은 key", NOW)).id();
        backfill.run(BackfillDirection.SEAL, ALL);
        assertThat(sealedCount(ids)).isEqualTo(2);
        String lostCipher = (String)rowOf(lost).get("CONTENT");
        jdbc.update("DELETE FROM user_data_key WHERE user_id = ?", stranger.id());
        dataKeys.forgetCachedKeys();

        backfill.run(BackfillDirection.UNSEAL, ALL);

        assertThat(rowOf(ids.get(0)).get("CONTENT")).isEqualTo("평문-표식-1141 하나");
        assertThat(rowOf(ids.get(0)).get("CONTENT_KEY_ID")).isNull();
        assertThat(rowOf(ids.get(1)).get("CONTENT")).isEqualTo("평문-표식-1142 둘");
        assertThat(rowOf(ids.get(1)).get("CONTENT_KEY_ID")).isNull();
        assertThat(rowOf(lost).get("CONTENT")).isEqualTo(lostCipher);
        assertThat(rowOf(lost).get("CONTENT_KEY_ID")).isNotNull();
        assertThat(contentsOf(conversation.id())).containsExactly("평문-표식-1141 하나", "평문-표식-1142 둘");
        assertThat(output.getAll()).contains("direction=UNSEAL").doesNotContain("평문-표식");

        // 다시 암호화하면 원래대로 돌아온다. 다른 검사가 남긴 줄도 암호문으로 되돌려 둔다
        backfill.run(BackfillDirection.SEAL, ALL);
        assertThat(sealedCount(ids)).isEqualTo(2);
    }


    @Test
    @DisplayName("암호문 제목도 UNSEAL 이 평문으로 되돌린다")
    void unsealRestoresTitle() {
        assertThat(titleRowOf(conversation.id()).get("TITLE_KEY_ID")).isNotNull();

        backfill.run(BackfillDirection.UNSEAL, ALL);

        Map<String, Object> row = titleRowOf(conversation.id());
        assertThat(row.get("TITLE")).isEqualTo("월급 확인");
        assertThat(row.get("TITLE_KEY_ID")).isNull();
        backfill.run(BackfillDirection.SEAL, ALL);
        assertThat(titleRowOf(conversation.id()).get("TITLE_KEY_ID")).isNotNull();
    }


    @Test
    @DisplayName("대상 번호를 읽은 뒤 사용자의 새 쓰기가 먼저 커밋되면 그 줄을 덮어쓰지 않는다")
    void doesNotOverwriteRowWrittenAfterIdsWereRead() throws Exception {
        List<Long> ids = plainMessages("평문-표식-1151 옛 본문");
        Long id = ids.get(0);
        Object newerKey = jdbc.queryForObject("SELECT id FROM user_data_key WHERE user_id = ?", Object.class, owner.id());
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 사용자의 쓰기: 대화 줄을 잠그고 그 메시지를 새 값으로 고친다. 커밋 전에 배치가 같은 줄을 잡으려 한다
            Future<?> userWrite = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                conversations.findByIdForMessageWrite(conversation.id()).orElseThrow();
                locked.countDown();
                sleep(500);
                jdbc.update(
                    "UPDATE chat_message SET content = ?, content_key_id = ? WHERE id = ?", "사용자가 새로 쓴 값", newerKey, id);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> batch = pool.submit(() -> sealer.sealMessage(id));

            userWrite.get(30, TimeUnit.SECONDS);
            assertThat(batch.get(30, TimeUnit.SECONDS)).isFalse();
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("CONTENT")).isEqualTo("사용자가 새로 쓴 값");
        assertThat(row.get("CONTENT_KEY_ID")).isEqualTo(newerKey);
    }


    private List<Long> plainMessages(String... contents) {
        // 암호화해 저장한 뒤 평문과 빈 key 칸으로 돌려, 키를 넣기 전에 저장한 옛 줄을 만든다
        return Arrays.stream(contents).map(content -> {
            Long id = messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), "임시", NOW)).id();
            jdbc.update("UPDATE chat_message SET content = ?, content_key_id = NULL WHERE id = ?", content, id);
            return id;
        }).toList();
    }


    private long sealedCount(List<Long> ids) {
        return ids.stream().filter(id -> rowOf(id).get("CONTENT_KEY_ID") != null).count();
    }


    private List<String> contentsOf(Long conversationId) {
        return messages.findByConversationIdOrderByIdAsc(conversationId).stream().map(ChatMessage::content).toList();
    }


    private Map<String, Object> rowOf(Long messageId) {
        return jdbc.queryForMap("SELECT content, content_key_id FROM chat_message WHERE id = ?", messageId);
    }


    private Map<String, Object> titleRowOf(Long conversationId) {
        return jdbc.queryForMap("SELECT title, title_key_id FROM conversation WHERE id = ?", conversationId);
    }


    private AppUser user() {
        String email = "backfill-" + UUID.randomUUID() + "@example.test";
        return users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
    }


    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
