package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 메시지 저장과 빈 작업 대화 삭제를 별도 트랜잭션에서 경합시킨다. MySQL 검사도 같은 계약을 상속한다. */
@SpringBootTest
@ActiveProfiles("test")
class ChatMessageParentGuardTest {

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Long conversationId;
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        conversationId = taskConversation().id();
    }

    @AfterEach
    void tearDown() {
        transaction().executeWithoutResult(status -> {
            createdIds.forEach(id -> messages.deleteAll(messages.findByConversationIdOrderByIdAsc(id)));
            createdIds.forEach(conversations::deleteById);
        });
        createdIds.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "saveAndFlush", "saveAll", "saveAllAndFlush"})
    @DisplayName("모든 저장 경로가 이미 지운 작업 대화의 늦은 메시지를 거절한다")
    void rejectsMessagesAfterConversationDeletion(String method) {
        assertThat(discard(conversationId)).isEqualTo(1);

        assertThatThrownBy(() -> saveWith(method, message(conversationId)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));

        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "saveAndFlush", "saveAll", "saveAllAndFlush"})
    @DisplayName("모든 저장 경로가 새 메시지를 저장하고 기존 메시지는 중복 없이 다시 저장한다")
    void savesAndMergesMessagesWithExistingParent(String method) {
        ChatMessage saved = saveWith(method, message(conversationId));
        assertThat(saved.id()).isNotNull();

        ChatMessage merged = saveWith(method, saved);

        assertThat(merged.id()).isEqualTo(saved.id());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"saveAll", "saveAllAndFlush"})
    @DisplayName("한 묶음에 지운 대화가 있으면 다른 대화의 메시지도 저장하지 않는다")
    void rejectsWholeBatchWhenOneParentIsMissing(String method) {
        Long missing = taskConversation().id();
        assertThat(discard(missing)).isEqualTo(1);
        List<ChatMessage> batch = List.of(message(conversationId), message(missing));

        assertThatThrownBy(() -> saveBatchWith(method, batch)).isInstanceOf(ApiException.class);

        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(missing)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"saveAll", "saveAllAndFlush"})
    @DisplayName("대화 번호가 역순인 묶음도 입력 순서대로 메시지를 돌려준다")
    void preservesBatchOrder(String method) {
        Long other = taskConversation().id();
        List<ChatMessage> batch = List.of(message(other), message(conversationId), message(other));

        List<ChatMessage> saved = saveBatchWith(method, batch);

        assertThat(saved).extracting(ChatMessage::conversationId).containsExactly(other, conversationId, other);
        assertThat(saved).allSatisfy(message -> assertThat(message.id()).isNotNull());
    }

    @Test
    @DisplayName("목록에서 숨겼지만 물리적으로 남은 대화에는 늦은 실행 결과를 저장한다")
    void savesResultsForSoftDeletedConversation() {
        transaction().executeWithoutResult(status -> conversations.deleteIfActive(conversationId, 1L, Instant.now()));

        ChatMessage saved = messages.save(message(conversationId));

        assertThat(saved.id()).isNotNull();
        assertThat(conversations.findById(conversationId).orElseThrow().deletedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("먼저 읽어 둔 대화가 다른 트랜잭션에서 지워져도 늦은 저장을 거절한다")
    void rejectsCachedParentAfterConcurrentDeletion() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                assertThat(conversations.findById(conversationId)).isPresent();
                try {
                    assertThat(executor.submit(() -> discard(conversationId)).get(10, TimeUnit.SECONDS))
                            .isEqualTo(1);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                assertThatThrownBy(() -> messages.save(message(conversationId))).isInstanceOf(ApiException.class);
                status.setRollbackOnly();
            });
        }
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).isEmpty();
    }

    @Test
    @DisplayName("메시지가 먼저 잠그면 옛 조회 결과를 가진 삭제도 커밋을 기다리고 대화를 보존한다")
    void preservesParentWhenMessageWriteWins() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> transaction().execute(status -> {
                ChatMessage message = messages.save(message(conversationId));
                saved.countDown();
                await(release);
                return message.id();
            }));
            try {
                await(saved);
                var cleanup = executor.submit(() -> transaction().execute(status -> {
                    // MySQL REPEATABLE READ 에서 메시지 커밋 전의 조회 시점을 먼저 만든다.
                    assertThat(messages.findByConversationIdOrderByIdAsc(conversationId))
                            .isEmpty();
                    deleting.countDown();
                    return discard(conversationId);
                }));
                await(deleting);
                assertThatThrownBy(() -> cleanup.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();

                assertThat(writer.get(10, TimeUnit.SECONDS)).isNotNull();
                assertThat(cleanup.get(10, TimeUnit.SECONDS)).isZero();
            } finally {
                release.countDown();
            }
        }
        assertThat(conversations.findById(conversationId)).isPresent();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).hasSize(1);
    }

    @Test
    @DisplayName("삭제가 먼저 잠그면 메시지 저장은 삭제 커밋 뒤 거절된다")
    void rejectsMessageWhenDeletionWins() throws Exception {
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cleanup = executor.submit(() -> transaction().execute(status -> {
                int count = discard(conversationId);
                deleted.countDown();
                await(release);
                return count;
            }));
            try {
                await(deleted);
                var writer = executor.submit(() -> {
                    writing.countDown();
                    assertThatThrownBy(() -> messages.save(message(conversationId)))
                            .isInstanceOf(ApiException.class);
                });
                await(writing);
                assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();

                assertThat(cleanup.get(10, TimeUnit.SECONDS)).isEqualTo(1);
                writer.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(conversations.findById(conversationId)).isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).isEmpty();
    }

    private int discard(Long id) {
        return transaction().execute(status -> conversations.discardEmptyTaskConversation(id));
    }

    private Conversation taskConversation() {
        Conversation conversation =
                conversations.save(Conversation.startedForTask(1L, "합성 작업 대화", null, 1L, Instant.now()));
        createdIds.add(conversation.id());
        return conversation;
    }

    private ChatMessage message(Long parentId) {
        return ChatMessage.fromSystem(parentId, "합성 메시지", Instant.now());
    }

    private ChatMessage saveWith(String method, ChatMessage message) {
        JpaRepository<ChatMessage, Long> repository = messages;
        return switch (method) {
            case "save" -> repository.save(message);
            case "saveAndFlush" -> repository.saveAndFlush(message);
            default -> saveBatchWith(method, List.of(message)).getFirst();
        };
    }

    private List<ChatMessage> saveBatchWith(String method, List<ChatMessage> batch) {
        JpaRepository<ChatMessage, Long> repository = messages;
        return switch (method) {
            case "saveAll" -> repository.saveAll(batch);
            case "saveAllAndFlush" -> repository.saveAllAndFlush(batch);
            default -> throw new IllegalArgumentException(method);
        };
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("다른 트랜잭션의 준비").isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
