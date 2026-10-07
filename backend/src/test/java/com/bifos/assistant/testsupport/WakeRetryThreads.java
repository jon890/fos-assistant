package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.fail;

import com.bifos.assistant.chat.application.WakeRetryDue;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.context.event.EventListener;

/**
 * 위임 결과 자동 turn 의 재시도 사건을 낸 스레드를 모은다. 그 스레드가 끝나면 사건을 받은 쪽의 일도 끝났다.
 *
 * <p>기본은 꺼져 있어 아무것도 모으지 않는다. 쓰는 검사가 {@link #start()} 로 켜고, {@link IntegrationTestIsolation} 이 검사 뒤에
 * {@link #reset()} 으로 끈다.
 */
public class WakeRetryThreads {
    private final BlockingQueue<Thread> threads = new LinkedBlockingQueue<>();
    private volatile boolean collecting;

    /** 모은 것을 비우고 이 뒤의 재시도 사건을 모은다. */
    public void start() {
        threads.clear();
        collecting = true;
    }

    /** 끄고 모은 것을 비운다. */
    public void reset() {
        collecting = false;
        threads.clear();
    }

    @EventListener
    public void onWakeRetryDue(WakeRetryDue event) {
        if (collecting) {
            threads.add(Thread.currentThread());
        }
    }

    /** 재시도 사건 하나가 나고 그 스레드가 끝날 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    public void awaitOne(Duration limit) throws InterruptedException {
        Thread thread = threads.poll(limit.toMillis(), TimeUnit.MILLISECONDS);
        if (thread == null) {
            fail("재시도 사건이 %s 안에 나지 않았다", limit);
        }
        if (!thread.join(limit)) {
            fail("재시도 스레드 %s 가 %s 안에 끝나지 않았다", thread.getName(), limit);
        }
    }
}
