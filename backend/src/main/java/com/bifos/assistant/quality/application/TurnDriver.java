package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.TurnOutcome;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.dto.TurnMark;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 평가 대화에 문항 하나를 보내고 끝나기를 기다린다. 배포 뒤 확인과 묶음 평가가 함께 쓴다(docs/features/agent-quality/design.md 의 「확인 한 번의 흐름」).
 *
 * <p>{@link ChatService#send} 는 끝날 때까지 막히고, turn 취소 표시는 Hermes run 을 멈추지 않는다. 그래서 문항은 별도 작업에서 보내고 부른 스레드는
 * 끝남 조건을 {@code poll-interval} 마다 본다. 마감이 지나면 도는 turn 과 위임 자식을 멈춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TurnDriver {

    /** 마감 뒤 멈춘 turn 이 닫히기를 기다리는 상한이다. 넘으면 다음으로 넘어간다. */
    static final Duration STOP_GRACE = Duration.ofMinutes(2);

    private static final Set<ErrorCode> BUSY = Set.of(ErrorCode.USER_BUSY, ErrorCode.CONVERSATION_BUSY);

    private final ChatService chat;
    private final TurnCancellation turns;
    private final AgentDelegationService delegations;
    private final DeployCheckObserver observer;
    private final BackgroundTasks background;
    private final LiveProperties<QualityProperties> properties;
    private final Clock clock;


    /** 문항 하나를 보내고 끝나기를 기다린다. 마감이 지나면 도는 turn 을 멈추고 시간 초과다. */
    TurnOutcome send(CurrentUser evaluation, Long conversationId, String text, List<Long> attachmentIds, Instant deadline) {
        int rootsBefore = observer.roots(conversationId).size();
        CompletableFuture<Void> sent = new CompletableFuture<>();
        background.start("quality-send-" + conversationId, () -> {
            try {
                chat.send(evaluation, conversationId, text, null, attachmentIds);
                sent.complete(null);
            } catch (RuntimeException ex) {
                sent.completeExceptionally(ex);
            }
        });
        if (!waitUntil(deadline, sent::isDone)) {
            // 마감 때 아직 turn 을 열지 않았거나 실행 번호가 붙기 전일 수 있다. 끝날 때까지 되풀이해 멈춰, 늦게 열린 turn 도 제출 즉시 멈춘다.
            waitUntil(clock.instant().plus(STOP_GRACE), () -> {
                if (sent.isDone()) {
                    return true;
                }
                stopConversation(evaluation, conversationId);
                return false;
            });
            return TurnOutcome.TIMED_OUT;
        }
        try {
            sent.join();
            return TurnOutcome.DONE;
        } catch (RuntimeException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            // 실행 실패는 실행 줄에 남아 판정이 그 줄의 오류 코드로 읽는다. 실행 줄이 생기기 전의 거절은 BUSY 가 아니면 REJECTED 다.
            log.info("평가 문항이 예외로 끝났다 conversationId={} cause={}", conversationId, cause.toString());
            if (cause instanceof ApiException api && BUSY.contains(api.code())) {
                return TurnOutcome.BUSY;
            }
            return observer.roots(conversationId).size() == rootsBefore ? TurnOutcome.REJECTED : TurnOutcome.DONE;
        }
    }


    /**
     * 도는 turn 을 멈추고, 대화의 루트마다 도는 위임 자식을 멈춘다. 이미 끝났으면 아무것도 하지 않는다.
     *
     * <p>turn 을 열었지만 실행 번호가 붙기 전이면 취소 표시만 켠다. Hermes run 이 제출되는 즉시 그 표시를 보고 멈춘다.
     */
    void stopConversation(CurrentUser evaluation, Long conversationId) {
        TurnMark mark = turns.markOf(conversationId);
        if (mark.running() && mark.executionId() == null) {
            turns.findByConversation(conversationId).ifPresent(turns::cancel);
        }
        if (mark.executionId() != null) {
            try {
                chat.stop(evaluation, mark.executionId());
            } catch (ApiException ignored) {
                // 그 사이 끝났다.
            }
        }
        for (AgentExecution root : observer.roots(conversationId)) {
            try {
                delegations.stopRunningChildrenOf(root.id());
            } catch (RuntimeException ex) {
                log.warn("평가 대화의 위임 자식을 멈추지 못했다 rootExecutionId={}", root.id(), ex);
            }
        }
    }


    /** 대화에 도는 turn 이 없어질 때까지 기다린다. 사용자 실행 자리를 다음 문항과 겹쳐 쓰지 않게 한다. */
    void settle(CurrentUser evaluation, Long conversationId) {
        if (!waitUntil(clock.instant().plus(STOP_GRACE), () -> !turns.markOf(conversationId).running())) {
            log.warn("평가 대화에 turn 이 아직 돈다 conversationId={}", conversationId);
            stopConversation(evaluation, conversationId);
        }
    }


    /** 대화에 도는 turn 이 있는가. */
    boolean running(Long conversationId) {
        return turns.markOf(conversationId).running();
    }


    /** 조건이 참이 되면 참, 마감까지 참이 되지 않으면 거짓이다. */
    boolean waitUntil(Instant deadline, BooleanSupplier condition) {
        Duration interval = properties.current().pollInterval();
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (!clock.instant().isBefore(deadline)) {
                return false;
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
