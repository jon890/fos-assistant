package com.bifos.assistant.chat.application;

import com.bifos.assistant.usage.application.TurnSlot;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AccessLevel;
import lombok.Getter;

/** 이 프로세스에서 도는 대화 turn 하나의 중지 상태다. 칸은 {@link TurnCancellation} 만 고친다. */
@Getter
public final class TurnHandle {
    final Long userId;
    final Long conversationId;
    final AtomicBoolean cancelled = new AtomicBoolean();
    final AtomicBoolean stopConfirmed = new AtomicBoolean();
    final AtomicBoolean finished = new AtomicBoolean();
    final AtomicBoolean stopped = new AtomicBoolean();
    final List<TurnRunRef> runs = new CopyOnWriteArrayList<>();
    volatile Long executionId;
    volatile Closeable stream;
    volatile CompletableFuture<Boolean> firstStop = new CompletableFuture<>();
    volatile CompletableFuture<Void> streamGraceExpired = new CompletableFuture<>();
    volatile ScheduledFuture<?> closeTask;

    /** 이 turn 이 쥔 사용자 자리. 잠금을 풀 때 돌려준다. 여는 스레드와 닫는 스레드가 달라 {@code volatile} 이다. */
    @Getter(AccessLevel.NONE)
    volatile TurnSlot slot;

    TurnHandle(Long userId, Long conversationId) {
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public Long userId() {
        return userId;
    }

    public AtomicBoolean cancelled() {
        return cancelled;
    }
}
