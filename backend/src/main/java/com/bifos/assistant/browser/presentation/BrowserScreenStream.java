package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 로그인 화면의 사건을 SSE 하나로 흘린다.
 *
 * <p>SSE 자체의 시간 제한은 두지 않는다. 화면의 수명은 화면 세션이 정한다. 연결이 끝나거나 시간 초과나 오류가 나면 건 일을 한 번 부른다.
 * 사건 하나를 쓰는 동안 다른 사건이 끼어들지 않게 한 번에 하나만 쓴다. 쓰기 실패는 본문 없이 끊긴 것으로만 본다.
 * 끝내기는 막힌 쓰기를 기다리지 않고, 그 쓰기가 풀린 뒤 그 스레드가 emitter 를 끝낸다.
 */
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
