package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.AttachmentCleaner;
import com.bifos.assistant.chat.application.AttachmentImages;
import com.bifos.assistant.chat.application.AttachmentInspection;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationPurger;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.application.MediaObservationCleaner;
import com.bifos.assistant.chat.application.MediaObservationService;
import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
import com.bifos.assistant.chat.infra.MediaObservationRequestRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 요청 커밋과 파일 작업의 장벽을 H2와 실제 MySQL에서 같은 회귀로 확인한다. */
@BackendIntegrationTest
public class AttachmentDeletionBarrierTest {
    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");
    private static final byte[] IMAGE = new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3};

    @Autowired
    AttachmentService service;

    @Autowired
    AttachmentCleaner cleaner;

    @Autowired
    ConversationAccess access;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter writer;

    @Autowired
    ConversationPurger purger;

    @Autowired
    AppUserRepository users;

    @Autowired
    AttachmentStore store;

    @Autowired
    AttachmentProperties properties;

    @Autowired
    AttachmentImages images;

    @Autowired
    AttachmentInspection inspection;

    @Autowired
    ChatContentMutationCoordinator mutations;

    @Autowired
    MediaObservationCleaner observations;

    @Autowired
    MediaObservationService observationService;

    @Autowired
    MediaObservationRepository observationRows;

    @Autowired
    MediaObservationRequestRepository observationRequests;

    @Autowired
    TestClock clock;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser owner;
    private Conversation conversation;
    private ChatAttachment photo;
    private final List<Path> fixtureDirectories = new ArrayList<>();

    @AfterEach
    protected void removeFiles() throws IOException {
        for (Path directory : fixtureDirectories) {
            clearDirectory(directory);
        }
    }

    @BeforeEach
    protected void setUp() {
        clock.set(NOW);
        attachments.deleteAll();
        owner = user();
        conversation = conversations.save(Conversation.startedBy(owner.id(), "삭제 장벽", null, NOW));
        photo = photo(owner, conversation);
    }

    @Test
    @DisplayName("삭제 요청 커밋과 파일 작업 전후에 접근을 막고 완료 기록 전 종료를 기동 정리로 복구한다")
    protected void retainsBarrierBeforeDuringAndAfterFileDeletion() {
        observationService.record(
                owner,
                conversation.id(),
                photo.id(),
                0,
                UUID.randomUUID(),
                ObservationFixture.input(),
                ObservationFixture.model());
        AttachmentStore interruptedStore = new AttachmentStore(properties) {
            @Override
            public synchronized void delete(ChatAttachment attachment) {
                assertBlocked();
                assertThat(observationRows.findFirstByAttachmentIdOrderByRevisionDesc(photo.id()))
                        .isEmpty();
                assertThat(observationRequests.findAll())
                        .noneMatch(row -> photo.id().equals(row.attachmentId()));
                assertThat(file(photo)).exists();
                assertThat(attachments.findById(photo.id()).orElseThrow().deletedAt())
                        .isNull();
                super.delete(attachment);
                assertBlocked();
                assertThat(file(photo)).doesNotExist();
                throw new IllegalStateException("synthetic stop before completion commit");
            }
        };
        var interrupted = new AttachmentCleaner(attachments, interruptedStore, clock, mutations, observations);
        assertThatThrownBy(() -> interrupted.delete(photo, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(attachments.findById(photo.id()).orElseThrow().deletionRequestedAt())
                .isEqualTo(NOW);
        assertThat(attachments.findById(photo.id()).orElseThrow().deletedAt()).isNull();
        clock.advance(Duration.ofSeconds(1));
        var restarted = new AttachmentCleaner(attachments, store, clock, mutations, observations);
        restarted.runScheduled();
        var completed = attachments.findById(photo.id()).orElseThrow();
        assertThat(completed.deletionRequestedAt()).isEqualTo(NOW);
        assertThat(completed.deletedAt()).isEqualTo(NOW.plusSeconds(1));
        assertBlocked();
    }

    @Test
    @DisplayName("파일 열기 중 삭제된 원본은 스트림을 닫고 응답 직전 SQL 검사에서 거절한다")
    protected void closesStreamWhenDeletionCommitsWhileOpening() {
        AtomicBoolean closed = new AtomicBoolean();
        AttachmentStore deletingStore = new AttachmentStore(properties) {
            @Override
            public synchronized InputStream open(ChatAttachment attachment) {
                InputStream opened = super.open(attachment);
                service.deleteByUser(owner, conversation.id(), photo.id());
                return new FilterInputStream(opened) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
            }
        };
        var reading = new AttachmentService(
                access, attachments, deletingStore, properties, clock, images, inspection, cleaner, manager);
        assertCode(() -> reading.read(owner, conversation.id(), photo.id()), ErrorCode.ATTACHMENT_GONE);
        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("원본 inspect와 overview도 읽는 중 요청이 커밋되면 바이트를 반환하지 않는다")
    protected void rejectsNativeBytesAfterConcurrentDeletionRequest() {
        for (int mode = 0; mode < 3; mode++) {
            photo = photo(owner, conversation);
            AttachmentStore deletingStore = new AttachmentStore(properties) {
                @Override
                public synchronized InputStream open(ChatAttachment attachment) {
                    InputStream opened = super.open(attachment);
                    service.deleteByUser(owner, conversation.id(), photo.id());
                    return opened;
                }
            };
            var reading = new AttachmentService(
                    access, attachments, deletingStore, properties, clock, images, inspection, cleaner, manager);
            int selected = mode;
            assertCode(
                    () -> {
                        if (selected == 0) {
                            reading.inspect(owner, conversation.id(), photo.id(), null);
                        } else if (selected == 1) {
                            reading.inspect(owner, conversation.id(), photo.id(), null, () -> true);
                        } else {
                            reading.inspectOverview(owner, conversation.id(), photo.id());
                        }
                    },
                    ErrorCode.ATTACHMENT_GONE);
        }
    }

    @Test
    @DisplayName("옛 트랜잭션의 첨부 엔티티가 보여도 다른 트랜잭션의 삭제 요청을 최신 SQL로 확인한다")
    protected void ignoresCachedEntityWhenSqlHasDeletionRequest() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            ChatAttachment cached = attachments.findById(photo.id()).orElseThrow();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                pool.submit(() -> service.deleteByUser(owner, conversation.id(), photo.id()))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            assertThat(cached.isVisible()).isTrue();
            assertCode(() -> service.read(owner, conversation.id(), photo.id()), ErrorCode.ATTACHMENT_GONE);
            assertCode(
                    () -> service.validateInspection(owner, conversation.id(), photo.id()), ErrorCode.ATTACHMENT_GONE);
        });
    }

    @Test
    @DisplayName("주인의 사용자 잠금에서 삭제가 대기하고 다른 사용자의 삭제는 독립적으로 끝난다")
    protected void serializesOwnerWithoutLockingOtherUsers() throws Exception {
        Conversation waitingConversation =
                conversations.save(Conversation.startedBy(owner.id(), "같은 주인의 다른 대화", null, NOW));
        ChatAttachment waitingPhoto = photo(owner, waitingConversation);
        CurrentUser otherOwner = user();
        Conversation other = conversations.save(Conversation.startedBy(otherOwner.id(), "독립 정리", null, NOW));
        ChatAttachment otherPhoto = photo(otherOwner, other);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        boolean mysql = jdbc.execute((ConnectionCallback<Boolean>)
                connection -> "MySQL".equals(connection.getMetaData().getDatabaseProductName()));
        var holdingSession = new AtomicReference<Long>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 사용자 행만 잠근다. 대화·첨부 잠금으로 사용자 잠금 누락을 가릴 수 없다.
            var holding = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                holdingSession.set(
                        jdbc.queryForObject(mysql ? "select connection_id()" : "select session_id()", Long.class));
                users.findByIdForUpdate(owner.id()).orElseThrow();
                entered.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var waiting =
                        pool.submit(() -> service.deleteByUser(owner, waitingConversation.id(), waitingPhoto.id()));
                assertUserLockWait(mysql, holdingSession.get(), waiting);
                pool.submit(() -> service.deleteByUser(otherOwner, other.id(), otherPhoto.id()))
                        .get(10, TimeUnit.SECONDS);
                assertThat(attachments.findById(otherPhoto.id()).orElseThrow().deletedAt())
                        .isEqualTo(NOW);
                release.countDown();
                waiting.get(10, TimeUnit.SECONDS);
                holding.get(10, TimeUnit.SECONDS);
                assertThat(attachments.findById(waitingPhoto.id()).orElseThrow().deletedAt())
                        .isEqualTo(NOW);
            } finally {
                release.countDown();
            }
        }
    }

    private void assertUserLockWait(boolean mysql, Long holdingSession, Future<?> waiting) throws InterruptedException {
        String sql = mysql
                ? "select count(*) from performance_schema.data_lock_waits w"
                        + " join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id"
                        + " join performance_schema.threads t on t.thread_id=w.blocking_thread_id"
                        + " where l.object_schema=database() and l.object_name='app_user' and t.processlist_id=?"
                : "select count(*) from information_schema.sessions where blocker_id=?"
                        + " and lower(executing_statement) like '%app_user%'"
                        + " and lower(executing_statement) like '%for update%'";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean observed = false;
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(sql, Long.class, holdingSession) > 0) {
                observed = true;
                break;
            }
            if (waiting.isDone()) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(observed).as("DB가 보유 세션의 app_user 행 잠금을 기다리는 요청을 확인해야 한다").isTrue();
        assertThat(waiting.isDone()).isFalse();
    }

    @Test
    @DisplayName("이미 열린 트랜잭션의 coordinator 진입과 writer 단독 호출을 거절한다")
    protected void rejectsSuffixOnlyTransactions() {
        assertThatThrownBy(() -> new TransactionTemplate(manager)
                        .execute(status -> mutations.run(
                                owner.id(),
                                new ChatContentMutationTarget(conversation.id(), List.of(photo.id())),
                                () -> true)))
                .isInstanceOf(IllegalStateException.class);
        Object purgeWriter = context.getBean("conversationPurgeWriter");
        writer.deleteIfActive(conversation.id(), owner.id(), NOW);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(purgeWriter, "purge", conversation.id(), NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager)
                        .execute(status ->
                                ReflectionTestUtils.invokeMethod(purgeWriter, "purge", conversation.id(), NOW)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attachments.findById(photo.id())).isPresent();
    }

    @Test
    @DisplayName("만료 정리의 파일 작업 전에도 사용자 삭제와 같은 영속 장벽을 유지한다")
    protected void usesSameBarrierForExpiry() {
        clock.advance(Duration.ofDays(30));
        AtomicBoolean checked = new AtomicBoolean();
        AttachmentStore validating = new AttachmentStore(properties) {
            @Override
            public synchronized void delete(ChatAttachment attachment) {
                assertBlocked();
                checked.set(true);
                super.delete(attachment);
            }
        };
        var expiring = new AttachmentCleaner(attachments, validating, clock, mutations, observations);
        assertThat(expiring.cleanExpired(clock.instant())).isEqualTo(1);
        assertThat(checked).isTrue();
        assertThat(attachments.findById(photo.id()).orElseThrow().deletionRequestedAt())
                .isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("만료 정리가 파일 삭제를 기다리는 동안 사용자 삭제가 완료되어도 첫 요청과 접근 차단을 유지한다")
    protected void handlesUserDeletionDuringExpiry() throws Exception {
        clock.advance(Duration.ofDays(30));
        Instant requestedAt = clock.instant();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AttachmentCleaner expiring = pausedCleaner(entered, release);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> expiring.cleanExpired(requestedAt));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofSeconds(1));
                pool.submit(() -> service.deleteByUser(owner, conversation.id(), photo.id()))
                        .get(10, TimeUnit.SECONDS);
                assertBlocked();
                assertThat(file(photo)).doesNotExist();
                assertThat(attachments.findById(photo.id()).orElseThrow().deletionRequestedAt())
                        .isEqualTo(requestedAt);
            } finally {
                release.countDown();
            }
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertBlocked();
        }
    }

    @Test
    @DisplayName("만료 정리가 파일 작업을 기다릴 때 대화 purge가 먼저 끝나도 파일과 행이 되살아나지 않는다")
    protected void handlesConversationPurgeDuringExpiry() throws Exception {
        clock.advance(Duration.ofDays(30));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AttachmentCleaner expiring = pausedCleaner(entered, release);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> expiring.cleanExpired(clock.instant()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                writer.deleteIfActive(conversation.id(), owner.id(), clock.instant());
                pool.submit(() -> purger.purgeDue(clock.instant())).get(10, TimeUnit.SECONDS);
                assertThat(conversations
                                .findById(conversation.id())
                                .orElseThrow()
                                .purgedAt())
                        .isEqualTo(clock.instant());
                assertThat(attachments.findById(photo.id())).isEmpty();
            } finally {
                release.countDown();
            }
            pending.get(10, TimeUnit.SECONDS);
            assertThat(file(photo)).doesNotExist();
            assertThat(attachments.findById(photo.id())).isEmpty();
        }
    }

    private AttachmentCleaner pausedCleaner(CountDownLatch entered, CountDownLatch release) {
        AttachmentStore paused = new AttachmentStore(properties) {
            @Override
            public synchronized void delete(ChatAttachment attachment) {
                entered.countDown();
                await(release);
                super.delete(attachment);
            }
        };
        return new AttachmentCleaner(attachments, paused, clock, mutations, observations);
    }

    private void assertBlocked() {
        assertCode(() -> service.read(owner, conversation.id(), photo.id()), ErrorCode.ATTACHMENT_GONE);
        assertCode(() -> service.inspect(owner, conversation.id(), photo.id(), null), ErrorCode.ATTACHMENT_GONE);
        assertCode(
                () -> service.inspect(owner, conversation.id(), photo.id(), null, () -> true),
                ErrorCode.ATTACHMENT_GONE);
        assertCode(() -> service.inspectOverview(owner, conversation.id(), photo.id()), ErrorCode.ATTACHMENT_GONE);
        assertCode(() -> service.validateInspection(owner, conversation.id(), photo.id()), ErrorCode.ATTACHMENT_GONE);
        assertThat(service.agentInput(conversation.id(), List.of(photo), "본문", true)
                        .images())
                .isEmpty();
        assertThat(service.agentInput(conversation.id(), List.of(photo), "본문", true)
                        .text())
                .contains("사진은 모두 1장")
                .doesNotContain("파일=" + photo.storedName());
    }

    private CurrentUser user() {
        AppUser row = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "주인", 1L, UserRole.MEMBER, NOW));
        Path directory = Path.of(properties.root(), "users", AttachmentStore.userDirectoryKey(row.id()));
        // 새 fixture의 사용자 영역만 비운다. DB를 새로 띄운 뒤 남은 파일이 같은 번호로 겹치지 않게 한다.
        try {
            clearDirectory(directory);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        fixtureDirectories.add(directory);
        return new CurrentUser(row.id(), row.email(), row.displayName(), row.groupId(), row.role());
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private ChatAttachment photo(CurrentUser user, Conversation target) {
        ChatAttachment row = service.upload(
                user, target.id(), "a.gif", "image/gif", IMAGE.length, () -> new ByteArrayInputStream(IMAGE));
        service.attach(100L, target.id(), List.of(row.id()));
        return attachments.findById(row.id()).orElseThrow();
    }

    private Path file(ChatAttachment attachment) {
        return Path.of(
                properties.root(),
                "users",
                AttachmentStore.userDirectoryKey(attachment.uploadedByUserId()),
                attachment.conversationId().toString(),
                attachment.storedName());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void assertCode(Runnable work, ErrorCode expected) {
        assertThatThrownBy(work::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
