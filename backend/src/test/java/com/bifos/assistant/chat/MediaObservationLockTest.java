package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.model.MediaObservationView;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL도 이 회귀와 요청 재시도/USER 회귀를 그대로 실행한다. */
class MediaObservationLockTest extends MediaObservationRequestTest {
    @Test
    @DisplayName("같은 CAS로 경쟁하는 새 요청은 revision 하나만 만든다")
    void createsOneRevisionForRacingNewRequestsWithSameCas() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> race(ready, start, UUID.randomUUID()));
            var second = pool.submit(() -> race(ready, start, UUID.randomUUID()));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results)
                    .filteredOn(value -> value instanceof MediaObservationView)
                    .hasSize(1);
            assertThat(results)
                    .filteredOn(value -> value == ErrorCode.MEDIA_OBSERVATION_CONFLICT)
                    .hasSize(1);
            assertThat(observations.count()).isEqualTo(1);
            assertThat(requests.count()).isEqualTo(1);
            assertThat(observations.findAll().getFirst().bodyKeyId()).isNotNull();
            assertThat(service.list(owner, conversation.id(), null, 10)
                            .getFirst()
                            .observation())
                    .isEqualTo(input());
        }
    }

    @Test
    @DisplayName("동일 UUID 경쟁은 alias 하나를 돌려준다")
    void returnsOneAliasForRacingIdenticalUuid() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        UUID id = UUID.randomUUID();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> race(ready, start, id));
            var second = pool.submit(() -> race(ready, start, id));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(observations.count()).isEqualTo(1);
            assertThat(requests.count()).isEqualTo(1);
        }
    }

    private Object race(CountDownLatch ready, CountDownLatch start, UUID id) {
        ready.countDown();
        await(start);
        try {
            return record(0, id, input(), model());
        } catch (ApiException ex) {
            return ex.code();
        }
    }

    @Test
    @DisplayName("저장 중 원본 해시는 실제 사용자 행 잠금으로 삭제를 기다리게 한다")
    void makesDeletionWaitOnRealUserRowWhileRecordHashesSource() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Long> holdingSession = new AtomicReference<>();
        boolean mysql = jdbc.execute((ConnectionCallback<Boolean>)
                connection -> "MySQL".equals(connection.getMetaData().getDatabaseProductName()));
        AttachmentStore paused = new AttachmentStore(properties) {
            @Override
            public InputStream open(ChatAttachment attachment) {
                holdingSession.set(
                        jdbc.queryForObject(mysql ? "select connection_id()" : "select session_id()", Long.class));
                InputStream stream = super.open(attachment);
                entered.countDown();
                await(release);
                return stream;
            }
        };
        var writing = local(paused, bodies, observations, requests, attachments, access);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> {
                try {
                    return writing.record(owner, conversation.id(), photo.id(), 0, UUID.randomUUID(), input(), model());
                } catch (ApiException ex) {
                    assertThat(ex.code()).isEqualTo(ErrorCode.ATTACHMENT_GONE);
                    return null;
                }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var deleting = pool.submit(() -> attachmentService.deleteByUser(owner, conversation.id(), photo.id()));
                assertUserLockWait(mysql, holdingSession.get(), deleting);
                release.countDown();
                pending.get(10, TimeUnit.SECONDS);
                deleting.get(10, TimeUnit.SECONDS);
                assertThat(observations.count()).isZero();
                assertThat(requests.count()).isZero();
                assertThat(attachments.findById(photo.id()).orElseThrow().deletionRequestedAt())
                        .isNotNull();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @DisplayName("조회 중 삭제는 완료되고 반환 전에 본문을 차단한다")
    void letsDeletionCommitWhileListReadsAndBlocksBodyBeforeReturn() throws Exception {
        record(0, UUID.randomUUID(), input(), model());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AttachmentStore paused = new AttachmentStore(properties) {
            @Override
            public InputStream open(ChatAttachment attachment) {
                InputStream opened = super.open(attachment);
                entered.countDown();
                await(release);
                return new FilterInputStream(opened) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
            }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var reading = pool.submit(() -> local(paused, bodies, observations, requests, attachments, access)
                    .list(owner, conversation.id(), null, 10));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                pool.submit(() -> attachmentService.deleteByUser(owner, conversation.id(), photo.id()))
                        .get(10, TimeUnit.SECONDS);
                assertThat(observations.count()).isZero();
                assertThat(requests.count()).isZero();
                assertThat(attachments.findById(photo.id()).orElseThrow().deletionRequestedAt())
                        .isNotNull();
                release.countDown();
                var result = reading.get(10, TimeUnit.SECONDS).getFirst();
                assertThat(result.observation()).isNull();
                assertThat(result.revision()).isNull();
                assertThat(result.sourceFingerprint()).isNull();
                assertThat(closed).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @DisplayName("저장 커밋 뒤 삭제와 현재 시각을 다시 확인한다")
    void rechecksDeletionAndClockAfterCommitBeforeReturningRecord() throws Exception {
        for (boolean expire : new boolean[] {false, true}) {
            photo = photo();
            clock.set(NOW);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger count = new AtomicInteger();
            var guarded = ChatAttachmentRepository.class.cast(Proxy.newProxyInstance(
                    ChatAttachmentRepository.class.getClassLoader(),
                    new Class<?>[] {ChatAttachmentRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("existsByIdAndConversationIdAndUploaded")
                                && count.incrementAndGet() == 2) {
                            entered.countDown();
                            await(release);
                        }
                        try {
                            return method.invoke(attachments, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    }));
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var pending = pool.submit(() -> code(
                        () -> local(store, bodies, observations, requests, guarded, access)
                                .record(owner, conversation.id(), photo.id(), 0, UUID.randomUUID(), input(), model()),
                        ErrorCode.ATTACHMENT_GONE));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(requests.count()).isEqualTo(1);
                    if (expire) {
                        clock.advance(Duration.ofDays(30));
                    } else {
                        attachmentService.deleteByUser(owner, conversation.id(), photo.id());
                    }
                    release.countDown();
                    pending.get(10, TimeUnit.SECONDS);
                    if (expire) {
                        assertThat(requests.count()).isEqualTo(1);
                        observationCleaner.cleanExpired(clock.instant());
                    }
                    assertThat(requests.count()).isZero();
                } finally {
                    release.countDown();
                }
            }
        }
    }

    private void assertUserLockWait(boolean mysql, Long holdingSession, Future<?> pending) throws InterruptedException {
        String sql = mysql
                ? "select count(*) from performance_schema.data_lock_waits w"
                        + " join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id"
                        + " join performance_schema.threads t on t.thread_id=w.blocking_thread_id"
                        + " where l.object_schema=database() and l.object_name='app_user' and t.processlist_id=?"
                : "select count(*) from information_schema.sessions where blocker_id=?"
                        + " and lower(executing_statement) like '%app_user%' and lower(executing_statement) like '%for update%'";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean observed = false;
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(sql, Long.class, holdingSession) > 0) {
                observed = true;
                break;
            }
            if (pending.isDone()) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(observed).as("다른 스레드의 삭제가 실제 app_user 행 잠금에서 기다린다").isTrue();
        assertThat(pending.isDone()).isFalse();
    }

    @Test
    @DisplayName("호출자의 트랜잭션을 중단하여 삭제 전 스냅샷으로 본문을 반환하지 않는다")
    void suspendsCallerTransactionAndIgnoresItsSnapshotAfterDeletionCommit() {
        record(0, UUID.randomUUID(), input(), model());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var cached = attachments.findById(photo.id()).orElseThrow();
            assertThat(observations.findAll()).hasSize(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                pool.submit(() -> attachmentService.deleteByUser(owner, conversation.id(), photo.id()))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            assertThat(cached.deletionRequestedAt()).isNull();
            var result = service.list(owner, conversation.id(), null, 10).getFirst();
            assertThat(result.observation()).isNull();
            assertThat(result.sourceFingerprint()).isNull();
            assertThat(result.revision()).isNull();
        });
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
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
}
