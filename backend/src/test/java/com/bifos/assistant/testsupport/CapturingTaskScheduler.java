package com.bifos.assistant.testsupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 시각 예약을 모아 두고 돌리지 않을 수 있는 스케줄러다.
 *
 * <p>운영에는 {@code TaskScheduler} 빈이 없고 Spring Boot 자동 설정의 {@link ThreadPoolTaskScheduler} 를 쓴다. 그 자동 설정은 다른
 * 스케줄러 빈이 있으면 만들어지지 않으므로, 이 빈이 {@code @Scheduled} 실행까지 맡는다. 기본은 꺼져 있어 모든 예약을 실제로 건다.
 * 쓰는 검사가 {@link #capture()} 로 켜면 {@link #schedule(Runnable, Instant)} 만 모아 두고, 그 밖의 예약은 그대로 실제 스케줄러로
 * 간다. {@link IntegrationTestIsolation} 이 검사 뒤에 {@link #reset()} 으로 끈다.
 */
public class CapturingTaskScheduler extends ThreadPoolTaskScheduler {
    private final List<Runnable> scheduled = new CopyOnWriteArrayList<>();
    private final List<Instant> startTimes = new CopyOnWriteArrayList<>();
    private volatile boolean capturing;

    /** 모아 둔 것을 비우고 이 뒤의 시각 예약을 모은다. */
    public void capture() {
        clear();
        capturing = true;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        if (!capturing) {
            return super.schedule(task, startTime);
        }
        scheduled.add(task);
        startTimes.add(startTime);
        return null;
    }

    /** 모아 둔 작업을 꺼내 비운다. */
    public List<Runnable> drain() {
        List<Runnable> tasks = new ArrayList<>(scheduled);
        scheduled.clear();
        return tasks;
    }

    /** 모은 예약의 시각이다. 예약한 순서다. */
    public List<Instant> startTimes() {
        return startTimes;
    }

    /** 모아 둔 작업과 시각을 비운다. 켜 둔 상태는 그대로다. */
    public void clear() {
        scheduled.clear();
        startTimes.clear();
    }

    /** 끄고 모아 둔 것을 비운다. */
    public void reset() {
        capturing = false;
        clear();
    }
}
