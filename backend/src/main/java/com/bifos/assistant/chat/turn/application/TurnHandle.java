package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.chat.turn.application.dto.TurnIntent;
import com.bifos.assistant.usage.execution.application.TurnSlot;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** 이 turn 의 루트 run 이다. steer 를 보낼 곳이다. 위임 run 이 섞이는 {@link #runs} 와 달리 {@code rekey} 가 비우지 않는다. */
    volatile TurnRunRef rootRun;
    /** 이 turn 의 종류다. steer 를 끼울 수 있는 turn 인지 가린다(ADR-20261116 / run-steer). */
    volatile TurnIntent intent;
    /** 지금 돌고 있는 Hermes 안 하위 에이전트의 수다. */
    final AtomicInteger openSubagents = new AtomicInteger();
    /** 이 turn 의 steer 말풍선을 정리했다. 그 뒤의 steer 는 거절로 처리한다. {@link #steerLock} 안에서만 세운다. */
    final AtomicBoolean steerSettled = new AtomicBoolean();
    /** steer 호출과 turn 끝의 정리를 차례로 돌리는 잠금이다. */
    final Object steerLock = new Object();

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


    /** steer 호출과 turn 끝의 정리가 함께 잡는 잠금이다. */
    public Object steerLock() {
        return steerLock;
    }


    /** 이 turn 의 steer 말풍선을 이미 정리했다. */
    public boolean steerSettled() {
        return steerSettled.get();
    }


    /** 정리했다고 적는다. {@link #steerLock()} 안에서 부른다. */
    public void markSteerSettled() {
        steerSettled.set(true);
    }
}
