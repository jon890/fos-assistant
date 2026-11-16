package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.SteerChild;
import com.bifos.assistant.chat.nextturn.config.SteerProperties;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.TurnHandle;
import com.bifos.assistant.chat.turn.application.TurnRunRef;
import com.bifos.assistant.chat.turn.application.dto.SkillCommand;
import com.bifos.assistant.chat.turn.application.dto.TurnIntent;
import com.bifos.assistant.hermes.application.dto.SteerOutcome;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.RunningDelegationQueries;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 답이 도는 중 입력창 글을 그 대화의 루트 실행에 끼워 넣는다(ADR-20261116 / run-steer).
 *
 * <p>끼울지 판정하고 Hermes 에 보낸 뒤, 결과에 따른 저장은 {@link SteerLedger} 에 맡긴다. Hermes 호출은 트랜잭션과 대화 줄 잠금
 * 밖에서, turn 의 steer 잠금 안에서 한다. turn 끝의 정리도 같은 잠금을 잡아, 응답을 기다리는 동안 turn 이 끝나도 받은 말풍선이
 * 정리에서 빠지지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SteerService {

    private final SteerProperties properties;
    private final TurnCancellation turns;
    private final HermesRunsClient hermes;
    private final SteerLedger ledger;
    private final RunningDelegationQueries delegations;


    /**
     * 이 글을 끼울 수 있는지 잠금 밖에서 빠르게 본다. 대기 줄이 비었는지는 더하는 쪽이 대화 줄을 잠근 뒤 본다.
     *
     * @param requested 화면이 입력창에 쓴 글이라고 표시했다. 카드 답과 다른 출처는 표시하지 않는다
     */
    public boolean eligible(Conversation conversation, String text, boolean requested) {
        if (!properties.enabled() || !requested || SkillCommand.parse(text).isPresent()) {
            return false;
        }
        return turns.findByConversation(conversation.id()).filter(SteerService::steerable).isPresent();
    }


    /**
     * 더한 대기 글을 끼워 넣는다. 「끼우는 중」 표시가 이미 붙은 글이다. 결과마다 {@link SteerLedger} 가 저장하고 사건을 낸다.
     *
     * <p>turn 이 그 사이 바뀌었거나 정리됐으면 Hermes 를 부르지 않고 거절과 같이 대기열로 둔다.
     */
    public void steer(Conversation conversation, Long pendingId, String text, Long userId) {
        Optional<TurnHandle> found = turns.findByConversation(conversation.id()).filter(SteerService::steerable);
        if (found.isEmpty()) {
            ledger.reject(conversation, pendingId);
            return;
        }
        TurnHandle handle = found.get();
        synchronized (handle.steerLock()) {
            if (handle.steerSettled()) {
                ledger.reject(conversation, pendingId);
                return;
            }
            TurnRunRef run = handle.getRootRun();
            Long executionId = handle.getExecutionId();
            SteerOutcome outcome;
            try {
                outcome = hermes.steer(run.getApiBaseUrl(), run.getProfileName(), run.getRunId(), text);
            } catch (RuntimeException ex) {
                // 보내기 전에 실패했다(profile key 를 읽지 못함 등). 「끼우는 중」 이 남으면 그 대화의 다음 turn 이 막힌다.
                log.warn("끼우기를 보내지 못해 대기열로 둔다 conversationId={}", conversation.id(), ex);
                ledger.reject(conversation, pendingId);
                return;
            }
            try {
                switch (outcome) {
                    case ACCEPTED -> ledger.accept(conversation, pendingId, text, userId, executionId, childOf(handle, conversation));
                    case REJECTED -> ledger.reject(conversation, pendingId);
                    case UNKNOWN -> ledger.unknown(conversation, pendingId, text, userId, executionId);
                }
            } catch (RuntimeException ex) {
                // 저장이 실패하면 「끼우는 중」 이 남아 그 대화의 다음 turn 이 막힌다. 받았는지 모르는 글로 정리를 다시 해 보고,
                // 그마저 실패하면 다음 tryNext 가 정리하게 적어 둔다.
                log.warn("끼우기 결과를 저장하지 못했다 conversationId={} outcome={}", conversation.id(), outcome, ex);
                recoverAfterSaveFailure(conversation, pendingId, text, userId, executionId, outcome);
            }
        }
    }


    /**
     * 끼우기 결과를 저장하지 못했을 때 「끼우는 중」 을 남기지 않는다. 거절이면 표시만 다시 지워 보고, 받음과 모름이면 받았는지 모르는 글로
     * 정리한다(같은 지시를 두 번 보내지 않는다). 그마저 실패하면 {@link SteerLedger#markStaleSteering} 로 적어 다음 turn 을 정할 때
     * 정리한다.
     */
    private void recoverAfterSaveFailure(
        Conversation conversation, Long pendingId, String text, Long userId, Long executionId, SteerOutcome outcome) {
        try {
            if (outcome == SteerOutcome.REJECTED) {
                ledger.reject(conversation, pendingId);
            } else {
                ledger.unknown(conversation, pendingId, text, userId, executionId);
            }
        } catch (RuntimeException ex) {
            log.warn("끼우기 결과 정리도 실패해 다음 차례에 다시 정리한다 conversationId={}", conversation.id(), ex);
            ledger.markStaleSteering(conversation.id());
        }
    }


    /** 끼울 수 있는 turn 이다. 사람이 보낸 질문, 대기 글, 다시 생성, 위임 결과 turn 이고 루트 run 이 있고 아직 정리되지 않았다. */
    private static boolean steerable(TurnHandle handle) {
        TurnIntent intent = handle.getIntent();
        boolean kind = intent instanceof TurnIntent.Fresh
            || intent instanceof TurnIntent.Regenerate
            || intent instanceof TurnIntent.DelegationResults;
        return kind && handle.getRootRun() != null && handle.getExecutionId() != null && !handle.steerSettled();
    }


    private SteerChild childOf(TurnHandle handle, Conversation conversation) {
        if (handle.getOpenSubagents().get() > 0) {
            return SteerChild.SUBAGENT;
        }
        return delegations.existsByConversationIdAndStatusAndDelegationKeyIsNotNull(conversation.id(), ExecutionStatus.RUNNING) ? SteerChild.DELEGATION : null;
    }
}
