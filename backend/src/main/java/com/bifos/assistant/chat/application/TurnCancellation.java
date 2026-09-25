package com.bifos.assistant.chat.application;

import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 이 프로세스에서 도는 대화 turn 과 Hermes 실행 중지 상태를 함께 관리한다. */
@Component
public class TurnCancellation {

    private static final Logger log = LoggerFactory.getLogger(TurnCancellation.class);
    private final ConcurrentHashMap<Long, TurnHandle> byConversation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TurnHandle> byExecution = new ConcurrentHashMap<>();
    private final HermesRunsClient hermes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Duration streamGrace;

    public TurnCancellation(HermesRunsClient hermes,
            @Value("${assistant.chat.stop-stream-grace:10s}") Duration streamGrace) {
        this.hermes = hermes;
        this.streamGrace = streamGrace;
    }

    public TurnHandle open(Long userId, Long conversationId) {
        TurnHandle handle = new TurnHandle(userId, conversationId);
        if (byConversation.putIfAbsent(conversationId, handle) != null) {
            throw new ApiException(ErrorCode.CONVERSATION_BUSY, "this conversation already has a running turn");
        }
        return handle;
    }

    public synchronized void rekey(TurnHandle handle, Long executionId) {
        Long old = handle.executionId;
        if (old != null) {
            byExecution.remove(old, handle);
        }
        handle.executionId = executionId;
        handle.runs.clear();
        byExecution.put(executionId, handle);
    }

    public Optional<TurnHandle> find(Long executionId) {
        return Optional.ofNullable(byExecution.get(executionId));
    }

    public void close(TurnHandle handle) {
        byConversation.remove(handle.conversationId, handle);
        if (handle.executionId != null) byExecution.remove(handle.executionId, handle);
        // 실행 번호가 붙기 전에 중지한 turn 은 새 run 없이 끝날 수 있다. 이 경우 중지 요청은
        // 성공으로 끝난 것이며, 이미 끝난 turn 과 구분해야 한다.
        handle.firstStop.complete(handle.cancelled.get() && !handle.finished.get());
    }

    public boolean isCancelled(Long executionId) {
        TurnHandle handle = byExecution.get(executionId);
        return handle != null && handle.cancelled.get();
    }

    /** Hermes 가 중지 요청을 받아들여 실제 취소로 전환된 turn 인지 본다. */
    public boolean isStopConfirmed(Long executionId) {
        TurnHandle handle = byExecution.get(executionId);
        return handle != null && handle.cancelled.get() && handle.stopConfirmed.get();
    }

    public boolean cancel(TurnHandle handle) {
        return handle.cancelled.compareAndSet(false, true);
    }

    /** 정상 완료 저장을 끝낸 turn 은 뒤늦은 중지 요청을 성공으로 답하지 않는다. */
    public void markFinished(TurnHandle handle) {
        handle.finished.set(true);
    }

    public boolean isFinished(TurnHandle handle) {
        return handle.finished.get();
    }

    /** Hermes 에 중지 요청을 보내지 못하면 turn 을 원래 실행 상태로 되돌린다. */
    public void resume(TurnHandle handle) {
        if (!handle.cancelled.compareAndSet(true, false)) return;
        ScheduledFuture<?> closeTask = handle.closeTask;
        if (closeTask != null) closeTask.cancel(false);
        handle.streamGraceExpired = new CompletableFuture<>();
        handle.firstStop = new CompletableFuture<>();
        handle.stopConfirmed.set(false);
    }

    /** Hermes 중지 요청이 모두 받아들여진 뒤에만 실행 경로가 취소로 분기한다. */
    public void confirmStop(TurnHandle handle) {
        if (!handle.cancelled.get() || !handle.stopConfirmed.compareAndSet(false, true)) return;
        handle.closeTask = scheduler.schedule(() -> {
            handle.streamGraceExpired.complete(null);
            Thread.startVirtualThread(() -> closeStream(handle));
        }, streamGrace.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 중지 요청은 시작됐지만 Hermes 가 아직 받아들이지 않았는지 본다. */
    public boolean isStopConfirmed(TurnHandle handle) {
        return handle.stopConfirmed.get();
    }

    /** 실행 번호가 아직 없어 Hermes 에 보낼 대상 없이 끝날 취소인지 본다. */
    public boolean shouldStopBeforeSubmit(Long executionId) {
        TurnHandle handle = byExecution.get(executionId);
        return handle != null && handle.cancelled.get() && handle.runs.isEmpty();
    }

    public boolean trackRun(Long executionId, String apiBaseUrl, String profileName, String runId) {
        TurnHandle handle = byExecution.get(executionId);
        if (handle == null) return true;
        RunRef run;
        synchronized (handle.runs) {
            run = handle.runs.stream().filter(it -> it.runId.equals(runId)).findFirst().orElse(null);
            if (run == null) {
                run = new RunRef(apiBaseUrl, profileName, runId);
                handle.runs.add(run);
            }
        }
        if (!handle.cancelled.get()) return true;
        boolean sent = stopRun(run);
        if (sent) confirmStop(handle);
        handle.firstStop.complete(sent);
        return sent;
    }

    /** 완료된 실행은 이후 자식을 멈출 때 다시 중지하지 않는다. */
    public void untrackRun(Long executionId, String runId) {
        TurnHandle handle = byExecution.get(executionId);
        if (handle == null || runId == null) return;
        handle.runs.removeIf(run -> run.runId.equals(runId));
    }

    public List<RunRef> pendingStops(TurnHandle handle) {
        return handle.runs.stream().filter(run -> !run.stopSent.get()).toList();
    }

    /** 같은 run 에 중지를 두 번 보내지 않고, 실패한 run 은 다음 요청에서 다시 보낸다. */
    public boolean stopRun(RunRef run) {
        synchronized (run) {
            if (run.stopSent.get()) return true;
            try {
                hermes.stop(run.apiBaseUrl, run.profileName, run.runId);
                run.stopSent.set(true);
                return true;
            } catch (RuntimeException ex) {
                log.warn("Hermes 실행을 멈추지 못했다 runId={}", run.runId, ex);
                return false;
            }
        }
    }

    public boolean hasRuns(TurnHandle handle) {
        return !handle.runs.isEmpty();
    }

    /** 일부 run 에 중지가 이미 전달됐으면 이전 실행 상태로 안전하게 되돌릴 수 없다. */
    public boolean hasStoppedRuns(TurnHandle handle) {
        return handle.runs.stream().anyMatch(run -> run.stopSent.get());
    }

    /** 중지가 제출보다 먼저 왔으면 첫 실행의 중지 결과를 기다린다. */
    public boolean awaitFirstStop(TurnHandle handle) {
        try {
            return handle.firstStop.get(streamGrace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException ex) {
            return false;
        }
    }

    public void attachStream(TurnHandle handle, Closeable stream) {
        handle.stream = stream;
        if (handle.streamGraceExpired.isDone()) {
            Thread.startVirtualThread(() -> closeStream(handle));
        }
    }

    public void detachStream(TurnHandle handle) {
        handle.stream = null;
    }

    public void awaitStreamOrGrace(TurnHandle handle, CompletableFuture<Void> streamDone) {
        CompletableFuture.anyOf(streamDone, handle.streamGraceExpired).join();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void closeStream(TurnHandle handle) {
        Closeable stream = handle.stream;
        if (stream == null) return;
        try { stream.close(); } catch (Exception ex) { log.warn("중지한 turn 의 스트림을 닫지 못했다", ex); }
    }

    @Getter
    public static final class TurnHandle {
        private final Long userId;
        private final Long conversationId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean stopConfirmed = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final List<RunRef> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile Long executionId;
        private volatile Closeable stream;
        private volatile CompletableFuture<Boolean> firstStop = new CompletableFuture<>();
        private volatile CompletableFuture<Void> streamGraceExpired = new CompletableFuture<>();
        private volatile ScheduledFuture<?> closeTask;
        private TurnHandle(Long userId, Long conversationId) { this.userId = userId; this.conversationId = conversationId; }
        public Long userId() { return userId; }
        public AtomicBoolean cancelled() { return cancelled; }
    }

    @Getter
    public static final class RunRef {
        private final String apiBaseUrl;
        private final String profileName;
        private final String runId;
        private final AtomicBoolean stopSent = new AtomicBoolean();
        private RunRef(String apiBaseUrl, String profileName, String runId) {
            this.apiBaseUrl = apiBaseUrl; this.profileName = profileName; this.runId = runId;
        }
    }
}
