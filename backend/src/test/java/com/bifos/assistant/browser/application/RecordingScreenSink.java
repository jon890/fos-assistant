package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

/** 받은 화면 사건을 모아 두는 대역 받는 쪽이다. */
final class RecordingScreenSink implements BrowserScreenSink {

    final List<Event> events = new CopyOnWriteArrayList<>();
    volatile boolean completed;
    volatile boolean failing;
    /** 주석 줄 쓰기가 실패한다. 받는 쪽이 말없이 끊긴 것과 같다. */
    volatile boolean pingFailing;
    /** 비어 있지 않으면 {@link #send} 가 이것이 열릴 때까지 막힌다. 읽지 않는 받는 쪽이다. */
    volatile CountDownLatch gate;
    /** 막힌 쓰기가 진행 중이다. */
    volatile boolean writing;

    private volatile Runnable onClose;

    /** 받은 사건 하나다. */
    record Event(String name, Object data) {}

    @Override
    public boolean send(String event, Object data) {
        CountDownLatch blocked = gate;
        if (blocked != null) {
            writing = true;
            try {
                blocked.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                writing = false;
            }
        }
        if (failing || completed) {
            return false;
        }
        events.add(new Event(event, data));
        return true;
    }

    @Override
    public boolean trySend(String event, Object data) {
        return !writing && send(event, data);
    }

    @Override
    public boolean ping() {
        return !pingFailing && !completed;
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public void onClose(Runnable callback) {
        onClose = callback;
    }

    /** 받는 쪽이 먼저 끊긴 것처럼 건 일을 부른다. */
    void disconnect() {
        completed = true;
        onClose.run();
    }

    List<Object> data(String name) {
        return events.stream()
                .filter(event -> event.name().equals(name))
                .map(Event::data)
                .toList();
    }

    /** 조건이 참이 될 때까지 2초까지 기다린다. */
    static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(10);
        }
    }
}
