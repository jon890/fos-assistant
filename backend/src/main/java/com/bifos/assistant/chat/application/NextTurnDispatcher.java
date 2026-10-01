package com.bifos.assistant.chat.application;

import com.bifos.assistant.orchestration.application.DelegationFinished;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * turn 이 닫힐 때, 위임이 끝났을 때, 서버가 뜰 때 다음 turn 을 정한다.
 *
 * <p>다음 turn 을 정하는 자리는 이것 하나다(ADR-047). 리스너를 여럿 걸면 같은 순간에 turn 잠금을 다투고 순서가 등록
 * 순서에 달리기 때문이다. 잠금은 {@link TurnCancellation} 의 메모리 맵이라 서버 하나를 전제로 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NextTurnDispatcher {

    private final TurnCancellation turns;
    private final DelegationWakeService wake;

    @PostConstruct
    void listenToTurnClose() {
        turns.addCloseListener(this::onTurnClosed);
    }

    void onTurnClosed(TurnClosed closed) {
        tryNext(closed.conversationId());
    }

    @EventListener
    public void onDelegationFinished(DelegationFinished event) {
        if (event.conversationId() != null) {
            tryNext(event.conversationId());
        }
    }

    /**
     * 기동 전에 끝났지만 전하지 못한 결과가 있는 대화를 차례로 이어 준다.
     *
     * <p>기동 정리가 끊긴 위임 실행을 FAILED 로 적은 뒤에 돈다. 그래야 그 결과도 함께 전한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void dispatchAfterStartup() {
        for (Long conversationId : wake.conversationsToWake()) {
            try {
                tryNext(conversationId);
            } catch (RuntimeException ex) {
                // 한 대화가 실패해도 나머지 대화는 이어 준다.
                log.warn("기동 뒤 대화를 깨우지 못했다 conversationId={}", conversationId, ex);
            }
        }
    }

    public void tryNext(Long conversationId) {
        wake.tryWake(conversationId);
    }
}
