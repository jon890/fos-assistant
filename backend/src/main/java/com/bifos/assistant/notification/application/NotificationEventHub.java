package com.bifos.assistant.notification.application;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 알림 사건을 그 사용자가 연 화면들에 나눠 준다(ADR-070).
 *
 * <p>창마다 구독이 하나다. 같은 사용자가 창을 여럿 열면 모든 창이 같은 사건을 받는다. 이 프로세스의 메모리에만 있다.
 * 서버가 다시 뜨면 구독도 사라지고 화면이 다시 연결한다.
 */
@Component
@Slf4j
public class NotificationEventHub {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<Consumer<NotificationEvent>>> subscribers =
            new ConcurrentHashMap<>();

    /**
     * 그 사용자의 알림 사건을 받는다. 돌려준 {@code Runnable} 을 부르면 더 받지 않는다.
     *
     * <p>같은 구독을 두 번 풀어도 된다.
     */
    public Runnable subscribe(Long userId, Consumer<NotificationEvent> consumer) {
        // 빈 목록을 지우는 해제와 겹쳐도 지워진 목록에 넣지 않게 맵 안에서 더한다.
        subscribers.compute(userId, (id, list) -> {
            CopyOnWriteArrayList<Consumer<NotificationEvent>> target =
                    list == null ? new CopyOnWriteArrayList<>() : list;
            target.add(consumer);
            return target;
        });
        return () -> unsubscribe(userId, consumer);
    }

    /** 그 사용자의 모든 구독자에게 보낸다. 보내다 예외가 난 구독자는 끊긴 연결로 보고 뺀다. */
    public void publish(Long userId, NotificationEvent event) {
        List<Consumer<NotificationEvent>> targets = subscribers.get(userId);
        if (targets == null) {
            return;
        }
        for (Consumer<NotificationEvent> consumer : targets) {
            try {
                consumer.accept(event);
            } catch (RuntimeException ex) {
                log.debug("알림 사건을 받지 못한 구독을 뺀다 userId={}", userId, ex);
                unsubscribe(userId, consumer);
            }
        }
    }

    private void unsubscribe(Long userId, Consumer<NotificationEvent> consumer) {
        subscribers.computeIfPresent(userId, (id, list) -> {
            list.remove(consumer);
            return list.isEmpty() ? null : list;
        });
    }
}
