package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.model.BrowserScreenSink;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 로그인 화면의 사건을 SSE 하나로 흘린다.
 *
 * <p>SSE 자체의 시간 제한은 두지 않는다. 화면의 수명은 화면 세션이 정한다. 연결이 끝나거나 시간 초과나 오류가 나면 건 일을 한 번 부른다.
 * 사건 하나를 쓰는 동안 다른 사건이 끼어들지 않게 한 번에 하나만 쓴다. 쓰기 실패는 본문 없이 끊긴 것으로만 본다.
 */
@Slf4j
final class BrowserScreenStream implements BrowserScreenSink {

    private final SseEmitter emitter = new SseEmitter(0L);
    private final ReentrantLock lock = new ReentrantLock();
    private boolean open = true;
    private Runnable onClose;

    BrowserScreenStream() {
        emitter.onCompletion(this::ended);
        emitter.onTimeout(this::ended);
        emitter.onError(error -> ended());
    }

    SseEmitter emitter() {
        return emitter;
    }

    @Override
    public boolean send(String event, Object data) {
        lock.lock();
        try {
            if (!open) {
                return false;
            }
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception ex) {
            open = false;
            log.debug("browser screen stream write failed event={}", event);
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void complete() {
        lock.lock();
        try {
            open = false;
        } finally {
            lock.unlock();
        }
        emitter.complete();
    }

    @Override
    public void onClose(Runnable callback) {
        lock.lock();
        try {
            onClose = callback;
            if (open) {
                return;
            }
        } finally {
            lock.unlock();
        }
        callback.run();
    }

    private void ended() {
        Runnable callback;
        lock.lock();
        try {
            open = false;
            callback = onClose;
        } finally {
            lock.unlock();
        }
        if (callback != null) {
            callback.run();
        }
    }
}
