package com.bifos.assistant.chat.application;

import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 이 프로세스에서 도는 대화 turn 과 Hermes 실행 중지 상태를 함께 관리한다. */
@Component
@Slf4j
public class TurnCancellation {
    private final ConcurrentHashMap<Long, TurnHandle> byConversation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TurnHandle> byExecution = new ConcurrentHashMap<>();
    private final HermesRunsClient hermes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Duration streamGrace;
    private final List<Consumer<TurnClosed>> closeListeners = new CopyOnWriteArrayList<>();

    public TurnCancellation(
            HermesRunsClient hermes, @Value("${assistant.chat.stop-stream-grace:10s}") Duration streamGrace) {
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

    /**
     * 그 대화에 도는 turn 이 없으면 잠금을 잡고 작업을 돌린 뒤 푼다. turn 의 모델 실행은 열지 않는다.
     *
     * <p>잠금을 푸는 자리에서 닫기 리스너가 불리므로, 그 사이 쌓인 대기 메시지와 결과가 이어서 간다. 도는 turn 이 있어
     * 잡지 못하면 작업을 돌리지 않고 거짓을 돌려준다. 그 turn 이 닫힐 때 호출한 쪽이 다시 시도해야 한다.
     *
     * <p>작업이 도는 동안 그 대화는 도는 turn 이 있는 것으로 보인다. {@link #markOf} 는 실행 번호가 없는 표시를 돌려주고
     * {@link #open} 은 {@code CONVERSATION_BUSY} 를 던진다. 부르는 쪽은 트랜잭션 하나처럼 짧은 작업만 넘기고, Hermes 호출처럼
     * 오래 걸리는 작업은 넘기지 않는다. 그 사이 거절된 보내기는 화면이 대기 메시지로 다시 넣고, 잠금을 풀 때 닫기 리스너가
     * 이어서 보낸다.
     *
     * @return 잠금을 잡아 작업을 돌렸다
     */
    public boolean runIfIdle(Long conversationId, Runnable work) {
        TurnHandle handle = new TurnHandle(null, conversationId);
        if (byConversation.putIfAbsent(conversationId, handle) != null) {
            return false;
        }
        try {
            work.run();
        } finally {
            close(handle);
        }
        return true;
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

    /**
     * 대화 번호로 도는 turn 표시를 읽는다. 표시를 바꾸지 않는다.
     *
     * <p>{@link #runIfIdle} 이 잡은 잠금도 도는 turn 으로 읽힌다. 그때 실행 번호는 null 이다.
     */
    public TurnMark markOf(Long conversationId) {
        TurnHandle handle = byConversation.get(conversationId);
        return handle == null ? TurnMark.NONE : new TurnMark(true, handle.executionId);
    }

    public Optional<TurnHandle> find(Long executionId) {
        return Optional.ofNullable(byExecution.get(executionId));
    }

    /**
     * turn 이 닫힐 때 그 대화 번호로 부를 것을 더한다.
     *
     * <p>맵에서 뺀 뒤에 부르므로, 받는 쪽은 그 대화에 새 turn 을 열 수 있다. 닫은 스레드에서 차례로 부른다.
     */
    public void addCloseListener(Consumer<TurnClosed> listener) {
        closeListeners.add(listener);
    }

    public void close(TurnHandle handle) {
        boolean removed = byConversation.remove(handle.conversationId, handle);
        if (handle.executionId != null) {
            byExecution.remove(handle.executionId, handle);
        }
        // 실행 번호가 붙기 전에 중지한 turn 은 새 run 없이 끝날 수 있다. 이 경우 중지 요청은
        // 성공으로 끝난 것이며, 이미 끝난 turn 과 구분해야 한다.
        handle.firstStop.complete(handle.cancelled.get() && !handle.finished.get());
        if (removed) {
            notifyClosed(new TurnClosed(handle.conversationId, handle.stopped.get()));
        }
    }

    /** 리스너의 예외가 turn 을 닫는 쪽으로 번지지 않게 경고 로그만 남긴다. */
    private void notifyClosed(TurnClosed closed) {
        for (Consumer<TurnClosed> listener : closeListeners) {
            try {
                listener.accept(closed);
            } catch (RuntimeException ex) {
                log.warn("turn 을 닫은 뒤의 후속 처리가 실패했다 conversationId={}", closed.conversationId(), ex);
            }
        }
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

    /** 이 turn 이 중지로 끝났다고 적는다. 닫기 리스너가 {@link TurnClosed#stopped()} 로 읽는다. */
    public void markStopped(TurnHandle handle) {
        handle.stopped.set(true);
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
        if (!handle.cancelled.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> closeTask = handle.closeTask;
        if (closeTask != null) {
            closeTask.cancel(false);
        }
        handle.streamGraceExpired = new CompletableFuture<>();
        handle.firstStop = new CompletableFuture<>();
        handle.stopConfirmed.set(false);
    }

    /** Hermes 중지 요청이 모두 받아들여진 뒤에만 실행 경로가 취소로 분기한다. */
    public void confirmStop(TurnHandle handle) {
        if (!handle.cancelled.get() || !handle.stopConfirmed.compareAndSet(false, true)) {
            return;
        }
        handle.closeTask = scheduler.schedule(
                () -> {
                    handle.streamGraceExpired.complete(null);
                    Thread.startVirtualThread(() -> closeStream(handle));
                },
                streamGrace.toMillis(),
                TimeUnit.MILLISECONDS);
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
        if (handle == null) {
            return true;
        }
        TurnRunRef run;
        synchronized (handle.runs) {
            run = handle.runs.stream()
                    .filter(it -> it.runId.equals(runId))
                    .findFirst()
                    .orElse(null);
            if (run == null) {
                run = new TurnRunRef(apiBaseUrl, profileName, runId);
                handle.runs.add(run);
            }
        }
        if (!handle.cancelled.get()) {
            return true;
        }
        boolean sent = stopRun(run);
        if (sent) {
            confirmStop(handle);
        }
        handle.firstStop.complete(sent);
        return sent;
    }

    /** 완료된 실행은 이후 자식을 멈출 때 다시 중지하지 않는다. */
    public void untrackRun(Long executionId, String runId) {
        TurnHandle handle = byExecution.get(executionId);
        if (handle == null || runId == null) {
            return;
        }
        handle.runs.removeIf(run -> run.runId.equals(runId));
    }

    public List<TurnRunRef> pendingStops(TurnHandle handle) {
        return handle.runs.stream().filter(run -> !run.stopSent.get()).toList();
    }

    /** 같은 run 에 중지를 두 번 보내지 않고, 실패한 run 은 다음 요청에서 다시 보낸다. */
    public boolean stopRun(TurnRunRef run) {
        synchronized (run) {
            if (run.stopSent.get()) {
                return true;
            }
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
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ex) {
            log.warn("중지한 turn 의 스트림을 닫지 못했다", ex);
        }
    }
}
