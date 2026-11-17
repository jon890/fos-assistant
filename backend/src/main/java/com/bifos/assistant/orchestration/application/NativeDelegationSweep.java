package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.nextturn.config.DelegationWakeProperties;
import com.bifos.assistant.chat.turn.application.ConversationNotices;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hermes 내장 위임 하위 에이전트의 끝 소식이 1시간 동안 오지 않았거나 그 자식을 낳은 답을 사용자가 중지했으면, 답마다 알림 줄 하나를
 * 남긴다(ADR-20261117 / native-delegation-wake, 스펙 DECISION31, DECISION34, DECISION40, DECISION41). 자동 답은 열지 않는다.
 *
 * <p>깨울 자식이고 등록한 지 {@link #HORIZON} 이 안 된 줄만 본다. 이 기능 전의 등록 줄과 옛 플러그인의 줄은 깨울 자식이 아니고, 오래 꺼
 * 두었던 동안 쌓인 줄은 기간 밖이라 한꺼번에 알림 줄을 남기지 않는다. 설정({@code assistant.delegation-wake.enabled})이 꺼져 있으면
 * 돌지 않는다.
 *
 * <p>origin(답)마다 조건부 update 와 알림 줄 저장을 한 트랜잭션에 둔다. 바꾼 줄이 있을 때만 알림 줄을 남겨 같은 알림이 두 번 남지
 * 않는다. 커밋한 뒤 열린 화면에 알린다. 대화의 turn 잠금은 잡지 않는다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NativeDelegationSweep {

    /** 끝 소식을 기다리는 기한이다. */
    static final Duration DEADLINE = Duration.ofHours(1);

    /** 이보다 오래전에 등록한 줄은 보지 않는다. */
    static final Duration HORIZON = Duration.ofHours(24);

    static final String TIMEOUT_ONE = "하위 작업의 결과를 1시간 동안 받지 못했어요. 늦게 오면 이어서 알려 드릴게요";
    static final String TIMEOUT_MANY = "하위 작업 %d개의 결과를 1시간 동안 받지 못했어요. 늦게 오면 이어서 알려 드릴게요";
    static final String STOPPED = "중지한 답이 맡긴 하위 작업이 끝났어요. 이어서 하려면 메시지를 보내 주세요";

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final ConversationNotices notices;
    private final LiveProperties<DelegationWakeProperties> wakeProperties;
    private final TransactionTemplate transactions;
    private final BackgroundTasks backgroundTasks;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();


    /** 주기마다 새 가상 스레드에서 한 번 훑는다. 꺼져 있거나 앞 주기가 아직 돌면 아무것도 하지 않는다. */
    @Scheduled(
        fixedDelayString = "${assistant.native-delegation.sweep-delay:60s}",
        initialDelayString = "${assistant.native-delegation.sweep-delay:60s}")
    public void sweep() {
        if (!wakeProperties.current().enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            backgroundTasks.start("native-delegation-sweep", () -> {
                try {
                    sweepOnce(clock.instant());
                } catch (RuntimeException ex) {
                    log.warn("내장 위임 기한과 중지 알림을 훑지 못했다 exceptionClass={}", ex.getClass().getSimpleName(), ex);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException | Error ex) {
            running.set(false);
            log.warn("내장 위임 훑기 스레드를 띄우지 못했다", ex);
        }
    }


    /** 그 시각으로 한 번 훑는다. 검사가 시각을 넘겨 부른다. */
    public void sweepOnce(Instant now) {
        Instant after = now.minus(HORIZON);
        Instant until = now.minus(DEADLINE);
        List<Saved> saved = new ArrayList<>();
        for (Long origin : origins(bindings.findTimeoutCandidates(after, until))) {
            noticeTimeout(origin, after, until, now).ifPresent(saved::add);
        }
        for (Long origin : origins(bindings.findStopCandidates(after))) {
            noticeStopped(origin, until, now).ifPresent(saved::add);
        }
        saved.forEach(line -> notices.publish(line.conversationId(), line.message()));
    }


    /** 앞서 알림 줄을 남긴 origin 이면 칸만 적는다. 아니면 칸을 적은 수만큼의 글로 알림 줄 하나를 남긴다. */
    private Optional<Saved> noticeTimeout(Long origin, Instant after, Instant until, Instant now) {
        Optional<Long> conversationId = conversationOf(origin);
        if (conversationId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.execute(status -> {
            boolean noticed = bindings.countTimeoutNoticed(origin) > 0;
            int changed = bindings.markTimeoutNoticed(origin, after, until, now);
            if (changed == 0 || noticed) {
                return null;
            }
            String text = changed == 1 ? TIMEOUT_ONE : TIMEOUT_MANY.formatted(changed);
            return notices.save(conversationId.get(), text).map(message -> new Saved(conversationId.get(), message)).orElse(null);
        }));
    }


    /** 그 origin 의 깨울 자식이 모두 끝났거나 가장 먼저 등록한 자식이 기한을 넘었으면 모두 전했다고 적고 알림 줄 하나를 남긴다. */
    private Optional<Saved> noticeStopped(Long origin, Instant until, Instant now) {
        List<HermesSessionBinding> children = bindings.findEligibleByOrigin(origin);
        boolean allStopped = children.stream().allMatch(child -> child.stoppedAt() != null);
        boolean overdue = children.stream()
            .map(HermesSessionBinding::createdAt)
            .min(Comparator.naturalOrder())
            .filter(first -> !first.isAfter(until))
            .isPresent();
        Optional<Long> conversationId = conversationOf(origin);
        if (!(allStopped || overdue) || conversationId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.execute(status -> {
            if (bindings.markStopDelivered(origin, now) == 0) {
                return null;
            }
            return notices.save(conversationId.get(), STOPPED).map(message -> new Saved(conversationId.get(), message)).orElse(null);
        }));
    }


    private Optional<Long> conversationOf(Long origin) {
        return executions.findById(origin).map(AgentExecution::conversationId);
    }


    private static Set<Long> origins(List<HermesSessionBinding> rows) {
        Set<Long> origins = new LinkedHashSet<>();
        rows.forEach(row -> origins.add(row.originExecutionId()));
        return origins;
    }


    /** 커밋한 뒤 알릴 알림 줄이다. */
    private record Saved(Long conversationId, ChatMessage message) {
    }
}
