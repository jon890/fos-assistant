package com.bifos.assistant.notification.presentation;

import com.bifos.assistant.notification.application.NotificationEvent;
import com.bifos.assistant.notification.application.NotificationProperties;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자 단위 알림 사건을 끝나지 않는 SSE 로 흘린다(ADR-070).
 *
 * <p>대화 SSE 의 구독 스트림과 같은 동작이다. 열자마자 주석 {@code connected} 를 보내고, 정해 둔 간격마다 주석
 * {@code ping} 을 보낸다. 사건이 없는 동안 앞단 프록시가 연결을 끊지 않게 하려는 것이다. 주석 줄은 사건이 아니라 web 의
 * 파서가 건너뛴다. {@code chat} 이 층 순서에서 위라 그쪽 도우미를 쓰지 않고 여기 따로 둔다.
 */
@Component
@Slf4j
public class NotificationEventStreams {

    private final Duration heartbeat;

    public NotificationEventStreams(NotificationProperties properties) {
        this.heartbeat = properties.streamHeartbeat();
    }

    /**
     * 끝나지 않는 스트림을 열고 {@code subscribe} 가 넘기는 사건을 보낸다.
     *
     * <p>{@code subscribe} 는 사건을 받을 소비자를 걸고 해제용 {@code Runnable} 을 돌려준다. 연결이 끊기거나 끝나면
     * 해제하고 주석 줄도 멈춘다. 끊긴 뒤 사건이 오면 소비자가 예외를 던져 건 쪽이 구독을 빼게 한다.
     *
     * <p>열자마자 주석 줄 하나를 보낸다. 사건이 없으면 첫 주석 줄까지 응답 헤더도 나가지 않아, 그동안 클라이언트가 연결이
     * 열렸는지 알 수 없다. 핸들러가 돌려주기 전에 보낸 것은 emitter 가 모아 두었다가 응답이 열리면 먼저 쓴다.
     */
    public SseEmitter follow(Function<Consumer<NotificationEvent>, Runnable> subscribe) {
        SseEmitter emitter = new SseEmitter(0L);
        Channel channel = new Channel(emitter);
        channel.comment("connected");
        Runnable unsubscribe = subscribe.apply(event -> {
            if (!channel.send(event)) {
                throw new IllegalStateException("notification event stream is closed");
            }
        });
        Thread heartbeatThread = Thread.ofVirtual()
                .name("notification-follow-heartbeat-")
                .start(() -> {
                    try {
                        do {
                            Thread.sleep(heartbeat);
                        } while (channel.comment("ping"));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        unsubscribe.run();
                    }
                });
        Runnable close = () -> {
            channel.close();
            unsubscribe.run();
            heartbeatThread.interrupt();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        return emitter;
    }

    /**
     * 사건과 주석 줄이 한 연결에 겹쳐 쓰이지 않게 한 번에 하나만 보낸다.
     *
     * <p>가상 스레드가 쓰는 동안 carrier 를 붙잡지 않게 {@code synchronized} 대신 lock 을 쓴다.
     */
    private static final class Channel {

        private final SseEmitter emitter;
        private final ReentrantLock lock = new ReentrantLock();
        private boolean open = true;

        private Channel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        boolean send(NotificationEvent event) {
            return write(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        }

        boolean comment(String text) {
            return write(SseEmitter.event().comment(text));
        }

        /** 연결이 이미 끝났을 때 더 쓰지 않게만 한다. 끝내는 것은 연결 쪽이 이미 했다. */
        void close() {
            lock.lock();
            try {
                open = false;
            } finally {
                lock.unlock();
            }
        }

        private boolean write(SseEmitter.SseEventBuilder event) {
            lock.lock();
            try {
                if (!open) {
                    return false;
                }
                emitter.send(event);
                return true;
            } catch (Exception ex) {
                open = false;
                log.debug("could not send a notification event", ex);
                return false;
            } finally {
                lock.unlock();
            }
        }
    }
}
