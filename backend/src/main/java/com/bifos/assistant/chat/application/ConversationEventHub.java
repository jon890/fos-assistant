package com.bifos.assistant.chat.application;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 요청 없이 도는 turn 의 사건을 그 대화를 보는 쪽에 나눠 준다.
 *
 * <p>보통 turn 은 요청한 연결로 사건을 보낸다. 자동 turn 은 요청한 연결이 없으므로 대화 번호로 구독한 쪽에 보낸다.
 * 이 프로세스의 메모리에만 있다. 서버가 다시 뜨면 구독도 사라진다.
 */
@Component
@Slf4j
public class ConversationEventHub {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<Consumer<ChatEvent>>> subscribers =
            new ConcurrentHashMap<>();

    /**
     * 그 대화의 사건을 받는다. 돌려준 {@code Runnable} 을 부르면 더 받지 않는다.
     *
     * <p>같은 구독을 두 번 풀어도 된다.
     */
    public Runnable subscribe(Long conversationId, Consumer<ChatEvent> consumer) {
        // 빈 목록을 지우는 해제와 겹쳐도 지워진 목록에 넣지 않게 맵 안에서 더한다.
        subscribers.compute(conversationId, (id, list) -> {
            CopyOnWriteArrayList<Consumer<ChatEvent>> target = list == null ? new CopyOnWriteArrayList<>() : list;
            target.add(consumer);
            return target;
        });
        return () -> unsubscribe(conversationId, consumer);
    }

    /** 그 대화의 모든 구독자에게 보낸다. 보내다 예외가 난 구독자는 끊긴 연결로 보고 뺀다. */
    public void publish(Long conversationId, ChatEvent event) {
        List<Consumer<ChatEvent>> targets = subscribers.get(conversationId);
        if (targets == null) {
            return;
        }
        for (Consumer<ChatEvent> consumer : targets) {
            try {
                consumer.accept(event);
            } catch (RuntimeException ex) {
                log.debug("대화 사건을 받지 못한 구독을 뺀다 conversationId={}", conversationId, ex);
                unsubscribe(conversationId, consumer);
            }
        }
    }

    private void unsubscribe(Long conversationId, Consumer<ChatEvent> consumer) {
        subscribers.computeIfPresent(conversationId, (id, list) -> {
            list.remove(consumer);
            return list.isEmpty() ? null : list;
        });
    }
}
