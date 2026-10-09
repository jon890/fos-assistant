package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.model.AgentPurgeOutcome;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지운 지 {@code assistant.agents.purge-after} 가 지난 에이전트의 행을 실제로 지운다(ADR-20261009 / agent-purge).
 *
 * <p>한 에이전트를 이 순서로 지운다.
 *
 * <ol>
 *   <li>지금 지울 수 있는지 읽기 트랜잭션으로 먼저 본다. 지웠지만 정리되지 않은 대화가 있으면 다음 차례로 미룬다. 기다리는
 *       에이전트마다 Hermes 를 부르지 않으려고 거두기보다 앞에 둔다.
 *   <li>Control Plane 이 만든 profile 이면 한 번 더 거두고 스킬 디렉터리를 지운다. 지울 때 이미 거뒀어도 다시 거두면 그대로
 *       끝난다. 운영에서 만든 profile 은 Hermes 를 부르지 않는다.
 *   <li>{@link AgentPurgeWriter} 가 행을 잠그고 기다릴지를 한 번 더 본 뒤, 딸린 줄과 행을 한 트랜잭션으로 지운다.
 * </ol>
 *
 * <p>어느 단계든 실패하면 그 에이전트는 행이 남는다. 다음 차례에 처음부터 다시 한다. 실패가 이어지는 에이전트는 기다리는 간격을 한
 * 시간까지 두 배씩 늘리고, 그동안 후보에서 빼서 뒤에 지운 에이전트가 밀리지 않게 한다. 다섯 번 잇달아 실패하면 error 로그를
 * 남긴다. 이 간격은 메모리에만 둔다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentPurger {

    /** 한 차례에 보는 에이전트 수다. 먼저 지운 것부터 본다. */
    private static final int BATCH = 20;

    /** 한 차례에 쓰는 시간의 상한이다. 정리는 다른 주기 작업과 scheduler 스레드 하나를 함께 쓴다. */
    private static final Duration TICK_BUDGET = Duration.ofSeconds(20);

    /** 이만큼 잇달아 실패하면 error 로그로 알린다. 운영자가 원인을 찾아야 하는 에이전트다. */
    private static final int ALERT_ATTEMPTS = 5;

    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /** 기다리는 에이전트가 없을 때 후보 질의에 넘기는 번호다. 에이전트 번호는 1부터다. */
    private static final List<Long> NONE_SKIPPED = List.of(-1L);

    private final AgentRepository agents;
    private final AgentPurgeWriter writer;
    private final ProfileProvisioning provisioner;
    private final ProfileSkillFiles skillFiles;
    private final AgentProperties properties;
    private final Clock clock;

    /** 실패한 에이전트를 다음에 다시 볼 시각과 그때 기다릴 간격, 잇단 실패 수다. */
    private final Map<Long, Backoff> backoffs = new ConcurrentHashMap<>();

    /** 운영은 매분 돈다. 검사에서는 {@code -} 로 끄고 {@link #purgeDue} 를 직접 부른다. */
    @Scheduled(cron = "${assistant.agents.purge-cron}")
    public void runScheduled() {
        purgeDue(clock.instant());
    }

    /**
     * 지운 지 정리 기한이 지난 에이전트를 지운다. 기다리는 간격 안의 에이전트는 후보에서 뺀다.
     *
     * @return 이번 차례에 지운 에이전트 수
     */
    public int purgeDue(Instant now) {
        Instant cutoff = now.minus(properties.purgeAfter());
        List<Long> waitingIds = backoffs.entrySet().stream()
                .filter(entry -> now.isBefore(entry.getValue().retryAt()))
                .map(Map.Entry::getKey)
                .toList();
        List<Long> candidates = agents.findPurgeCandidates(
                cutoff, waitingIds.isEmpty() ? NONE_SKIPPED : waitingIds, PageRequest.of(0, BATCH));
        long started = System.nanoTime();
        int purged = 0;
        int waiting = 0;
        int failed = 0;
        for (Long agentId : candidates) {
            if (Duration.ofNanos(System.nanoTime() - started).compareTo(TICK_BUDGET) > 0) {
                break;
            }
            try {
                AgentPurgeOutcome outcome = purgeOne(agentId, cutoff);
                if (outcome == AgentPurgeOutcome.PURGED) {
                    purged++;
                    backoffs.remove(agentId);
                } else if (outcome == AgentPurgeOutcome.WAITING) {
                    waiting++;
                }
            } catch (RuntimeException ex) {
                failed++;
                recordFailure(agentId, now, ex);
            }
        }
        if (purged > 0 || failed > 0) {
            log.info("지운 에이전트 정리 purged={} waiting={} failed={}", purged, waiting, failed);
        }
        return purged;
    }

    /** 에이전트 하나를 지운다. 기다려야 하면 Hermes 를 부르지 않고 {@code WAITING} 을 돌려준다. */
    private AgentPurgeOutcome purgeOne(Long agentId, Instant cutoff) {
        if (!writer.ready(agentId, cutoff)) {
            return AgentPurgeOutcome.WAITING;
        }
        Agent agent = agents.findById(agentId).orElse(null);
        if (agent == null) {
            return AgentPurgeOutcome.GONE;
        }
        if (agent.profileManaged()) {
            provisioner.deprovision(agent.hermesProfile());
            deleteSkillDirectory(agent);
        }
        return writer.purge(agentId, cutoff);
    }

    private void deleteSkillDirectory(Agent agent) {
        try {
            skillFiles.deleteAll(agent.hermesProfile());
        } catch (RuntimeException failure) {
            // 쓰이지 않을 디렉터리가 남을 뿐이라 행 정리는 멈추지 않는다. 로그에는 profile 이름을 적지 않는다.
            log.warn("지운 에이전트의 스킬 디렉터리를 지우지 못했다 agentId={}", agent.id());
        }
    }

    private void recordFailure(Long agentId, Instant now, RuntimeException ex) {
        Backoff previous = backoffs.get(agentId);
        Duration next =
                previous == null ? FIRST_BACKOFF : min(previous.interval().multipliedBy(2), MAX_BACKOFF);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        backoffs.put(agentId, new Backoff(now.plus(next), next, attempts));
        String code = ex instanceof ApiException api ? api.code().name() : "-";
        if (attempts == ALERT_ATTEMPTS) {
            log.error(
                    "지운 에이전트를 정리하지 못했다 agentId={} attempts={} error={} code={}",
                    agentId,
                    attempts,
                    ex.getClass().getSimpleName(),
                    code);
        } else {
            log.warn(
                    "지운 에이전트를 정리하지 못했다 agentId={} attempts={} error={} code={}",
                    agentId,
                    attempts,
                    ex.getClass().getSimpleName(),
                    code);
        }
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    /** 실패한 에이전트를 다시 볼 시각과 그때 쓴 간격, 잇단 실패 수다. */
    private record Backoff(Instant retryAt, Duration interval, int attempts) {}
}
