package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 로그인 화면의 사건을 시간 제한 없는 SSE 하나로 한 번에 하나씩 흘린다. 동작은 {@code docs/backend/user-browser.md} 가 갖는다. */
@Slf4j
final class BrowserScreenStream implements BrowserScreenSink {

    private final SseEmitter emitter;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile boolean open = true;
    private volatile Runnable onClose;

    BrowserScreenStream() {
        this(new SseEmitter(0L));
    }

    BrowserScreenStream(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(this::ended);
        emitter.onTimeout(this::ended);
        emitter.onError(error -> ended());
    }

    SseEmitter emitter() {
        return emitter;
    }

    @Override
    public boolean send(String event, Object data) {
        return Boolean.TRUE.equals(write(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON), true));
    }

    @Override
    public boolean trySend(String event, Object data) {
        return Boolean.TRUE.equals(write(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON), false));
    }

    @Override
    public boolean ping() {
        Boolean written = write(SseEmitter.event().comment("ping"), false);
        return written == null ? open : written;
    }

    @Override
    public void complete() {
        open = false;
        finish();
    }

    @Override
    public void onClose(Runnable callback) {
        onClose = callback;
        if (!open) {
            callback.run();
        }
    }

    /** 한 번에 하나만 쓴다. {@code wait} 가 거짓이고 다른 쓰기가 진행 중이면 {@code null} 이다. */
    private Boolean write(SseEmitter.SseEventBuilder event, boolean wait) {
        if (wait) {
            lock.lock();
        } else if (!lock.tryLock()) {
            return null;
        }
        try {
            if (!open) {
                return false;
            }
            emitter.send(event);
            return true;
        } catch (Exception ex) {
            open = false;
            log.debug("browser screen stream write failed");
            return false;
        } finally {
            lock.unlock();
            if (!open) {
                finish();
            }
        }
    }

    /** 쓰는 중이 아닐 때만 emitter 를 한 번 끝낸다. */
    private void finish() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (completed.compareAndSet(false, true)) {
                emitter.complete();
            }
        } finally {
            lock.unlock();
        }
    }

    private void ended() {
        open = false;
        Runnable callback = onClose;
        if (callback != null) {
            callback.run();
        }
    }
}
