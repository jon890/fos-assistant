package com.bifos.assistant.connector.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 커넥터의 선택지 조회, 등록, 연결 확인을 사용자마다 제한한다({@code backend/docs/flow.md} 의 「사용자별 호출 제한」).
 *
 * <p>넘으면 기다리게 하지 않고 거절한다. 줄을 세우면 요청 스레드와 DB 연결을 쥔 채로 쌓인다. 상태는 JVM 메모리에
 * 둔다. Control Plane 이 한 대이고, 재시작으로 횟수가 비워져도 잃는 것이 없다.
 */
@Component
public class ConnectorCallLimiter {
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final Map<Long, UserCalls> calls = new ConcurrentHashMap<>();
    private final ConnectorProperties properties;
    private final Clock clock;

    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorCallLimiter(ConnectorProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ConnectorCallLimiter(ConnectorProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 한도 안이면 {@code action} 을 돌리고, 끝나면 예외여도 동시 자리를 돌려준다.
     *
     * @throws ApiException 한도를 넘었을 때. {@code action} 을 부르지 않고 횟수에도 넣지 않는다
     */
    public <T> T call(Long userId, Supplier<T> action) {
        if (!acquire(userId)) {
            throw new ApiException(ErrorCode.CONNECTOR_RATE_LIMITED, "too many connector calls");
        }
        try {
            return action.get();
        } finally {
            release(userId);
        }
    }

    /** 판정과 갱신을 {@code compute} 안에서 해 같은 사용자의 요청이 겹쳐도 한 번에 하나만 본다. */
    private boolean acquire(Long userId) {
        Instant now = Instant.now(clock);
        boolean[] accepted = new boolean[1];
        calls.compute(userId, (id, current) -> {
            UserCalls state = current == null ? new UserCalls() : current;
            state.dropOlderThan(now.minus(WINDOW));
            if (state.running < properties.maxConcurrentCalls()
                    && state.startedAt.size() < properties.callsPerMinute()) {
                state.running++;
                state.startedAt.addLast(now);
                accepted[0] = true;
            }
            return state.isIdle() ? null : state;
        });
        return accepted[0];
    }

    private void release(Long userId) {
        Instant oldest = Instant.now(clock).minus(WINDOW);
        calls.computeIfPresent(userId, (id, state) -> {
            state.running--;
            state.dropOlderThan(oldest);
            return state.isIdle() ? null : state;
        });
    }

    /** 사용자 한 명의 상태다. {@code compute} 안에서만 읽고 쓴다. */
    private static final class UserCalls {
        private final Deque<Instant> startedAt = new ArrayDeque<>();
        private int running;

        private void dropOlderThan(Instant oldest) {
            while (!startedAt.isEmpty() && startedAt.peekFirst().isBefore(oldest)) {
                startedAt.removeFirst();
            }
        }

        /** 도는 호출도 남은 시각도 없으면 맵에 둘 까닭이 없다. */
        private boolean isIdle() {
            return running == 0 && startedAt.isEmpty();
        }
    }
}
