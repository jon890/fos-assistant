package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.shared.error.ApiException;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대화 한 차례의 사건을 SSE 로 흘린다.
 *
 * <p>모델이 생각만 하는 동안에는 보낼 사건이 없다. 229초 동안 사건이 없던 실행에서 앞단 프록시가 약
 * 100초 만에 연결을 끊었고, 화면은 실행이 도는 중인데 답을 받지 못했다고 보였다. 그래서 일이 도는
 * 동안 {@code heartbeat} 마다 SSE 주석 줄을 보낸다. 주석 줄은 사건이 아니라 web 의 파서가 건너뛴다.
 */
@Component
public class ChatEventStreams {

    private static final Logger log = LoggerFactory.getLogger(ChatEventStreams.class);

    private final Duration heartbeat;

    public ChatEventStreams(@Value("${assistant.chat.stream-heartbeat:20s}") Duration heartbeat) {
        this.heartbeat = heartbeat;
    }

    /** {@code work} 를 가상 스레드에서 돌리며 그것이 내는 사건을 보내고, 끝나면 스트림을 닫는다. */
    public SseEmitter open(Consumer<Consumer<ChatEvent>> work) {
        SseEmitter emitter = new SseEmitter(0L);
        Channel channel = new Channel(emitter);
        Thread worker = Thread.ofVirtual().name("chat-stream-").start(() -> {
            try {
                work.accept(channel::send);
            } catch (ApiException ex) {
                channel.send(ChatEvent.error(ex.code().name(), ex.getMessage()));
            } catch (Exception ex) {
                log.error("chat stream failed", ex);
                channel.send(ChatEvent.error("INTERNAL_ERROR", "internal error"));
            } finally {
                channel.complete();
            }
        });
        Thread.ofVirtual().name("chat-stream-heartbeat-").start(() -> beat(worker, channel));
        return emitter;
    }

    /**
     * 끝나지 않는 스트림을 열고 {@code subscribe} 가 넘기는 사건을 보낸다.
     *
     * <p>{@code subscribe} 는 사건을 받을 소비자를 걸고 해제용 {@code Runnable} 을 돌려준다. 연결이 끊기거나
     * 끝나면 해제하고 주석 줄도 멈춘다. 끊긴 뒤 사건이 오면 소비자가 예외를 던져 건 쪽이 구독을 빼게 한다.
     */
    public SseEmitter follow(Function<Consumer<ChatEvent>, Runnable> subscribe) {
        SseEmitter emitter = new SseEmitter(0L);
        Channel channel = new Channel(emitter);
        Runnable unsubscribe = subscribe.apply(event -> {
            if (!channel.send(event)) {
                throw new IllegalStateException("chat event stream is closed");
            }
        });
        Thread heartbeatThread = Thread.ofVirtual().name("chat-follow-heartbeat-").start(() -> {
            try {
                do {
                    Thread.sleep(heartbeat);
                } while (channel.ping());
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

    private void beat(Thread worker, Channel channel) {
        try {
            while (!worker.join(heartbeat)) {
                if (!channel.ping()) {
                    return;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 사건과 주석 줄이 한 연결에 겹쳐 쓰이지 않게 한 번에 하나만 보낸다.
     *
     * <p>브라우저가 끊긴 뒤에는 아무것도 보내지 않는다. 그래도 일은 끝까지 돌아 Hermes 실행의 최종 상태를
     * 읽고 실행 기록을 남긴다. 가상 스레드가 쓰는 동안 carrier 를 붙잡지 않게 {@code synchronized} 대신
     * lock 을 쓴다.
     */
    private static final class Channel {

        private final SseEmitter emitter;
        private final ReentrantLock lock = new ReentrantLock();
        private boolean open = true;

        private Channel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        boolean send(ChatEvent event) {
            return write(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        }

        boolean ping() {
            return write(SseEmitter.event().comment("ping"));
        }

        void complete() {
            lock.lock();
            try {
                if (open) {
                    open = false;
                    emitter.complete();
                }
            } finally {
                lock.unlock();
            }
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
                log.debug("could not send a chat event", ex);
                return false;
            } finally {
                lock.unlock();
            }
        }
    }
}
