package com.bifos.assistant.browser.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class BrowserScreenStreamTest {

    @Test
    @DisplayName("쓰기가 막혀 있으면 closed 는 건너뛰고 끝내기는 바로 돌아오며, 막힌 쓰기가 풀리면 emitter 를 한 번 끝낸다")
    void completesWithoutWaitingForBlockedWrite() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        SseEmitter emitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                writing.countDown();
                try {
                    gate.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ex);
                }
            }

            @Override
            public void complete() {
                completions.incrementAndGet();
            }
        };
        BrowserScreenStream stream = new BrowserScreenStream(emitter);
        Thread writer = Thread.ofPlatform().start(() -> stream.send("frame", Map.of()));
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(stream.trySend("closed", Map.of("reason", "replaced"))).isFalse();
        assertThat(stream.ping()).isTrue();
        Thread closer = Thread.ofPlatform().start(stream::complete);
        closer.join(2000);
        assertThat(closer.isAlive()).isFalse();
        assertThat(completions.get()).isZero();

        gate.countDown();
        writer.join(2000);
        assertThat(completions.get()).isEqualTo(1);
        assertThat(stream.send("frame", Map.of())).isFalse();
        assertThat(stream.ping()).isFalse();
    }

    @Test
    @DisplayName("진행 중인 프레임 쓰기가 곧 끝나면 closed 는 잠깐 기다렸다가 보낸다")
    void sendsClosedAfterShortWrite() throws InterruptedException {
        CountDownLatch writing = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        SseEmitter emitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                if (sent.incrementAndGet() == 1) {
                    writing.countDown();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", ex);
                    }
                }
            }
        };
        BrowserScreenStream stream = new BrowserScreenStream(emitter);
        Thread writer = Thread.ofPlatform().start(() -> stream.send("frame", Map.of()));
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(stream.trySend("closed", Map.of("reason", "replaced"))).isTrue();
        writer.join(2000);
        assertThat(sent.get()).isEqualTo(2);
    }
}
