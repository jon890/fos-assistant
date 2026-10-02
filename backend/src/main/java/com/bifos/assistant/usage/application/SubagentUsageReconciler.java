package com.bifos.assistant.usage.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.ProfileModelDefaultsClient;
import com.bifos.assistant.hermes.dto.ProfileModelDefaults;
import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.SubagentUsageJob;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.infra.SubagentUsageJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대화 응답과 분리해 최종 자식 사용량과 profile 기본 강도를 보완한다.
 *
 * <p>재조회 작업 줄은 native 자식 한 명의 사용량 원장이다. 종료를 확인한 자식의 provider, 모델, 토큰,
 * 환산 금액을 그 줄에 한 번만 적고, 합계는 그 줄의 값을 더한다. 완료 사건은 표시용으로만 남긴다.
 * 근거는 ADR-059 에 있다.
 */
@Service
@Slf4j
public class SubagentUsageReconciler {
    private final AgentExecutionRepository executions;
    private final ExecutionEventRepository events;
    private final SubagentUsageJobRepository jobs;
    private final AgentService agents;
    private final HermesRunsClient hermes;
    private final ProfileModelDefaultsClient defaults;
    private final CostEstimator estimator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(4);
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    @Autowired
    public SubagentUsageReconciler(
            AgentExecutionRepository executions,
            ExecutionEventRepository events,
            SubagentUsageJobRepository jobs,
            AgentService agents,
            HermesRunsClient hermes,
            ProfileModelDefaultsClient defaults,
            CostEstimator estimator,
            PlatformTransactionManager manager) {
        this(executions, events, jobs, agents, hermes, defaults, estimator, manager, Clock.systemUTC());
    }

    SubagentUsageReconciler(
            AgentExecutionRepository executions,
            ExecutionEventRepository events,
            SubagentUsageJobRepository jobs,
            AgentService agents,
            HermesRunsClient hermes,
            ProfileModelDefaultsClient defaults,
            CostEstimator estimator,
            PlatformTransactionManager manager,
            Clock clock) {
        this.executions = executions;
        this.events = events;
        this.jobs = jobs;
        this.agents = agents;
        this.hermes = hermes;
        this.defaults = defaults;
        this.estimator = estimator;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Scheduled(cron = "${assistant.usage.reconcile-cron:*/5 * * * * *}")
    public void reconcile() {
        Instant now = clock.instant();
        discover(now);
        List<AgentExecution> unknownDefaults =
                executions.findUnknownReasoningDefaults(now.minus(Duration.ofHours(24)), PageRequest.of(0, 20));
        if (!unknownDefaults.isEmpty()) {
            launch("profile:defaults", () -> supplementDefaults(unknownDefaults));
        }
        for (SubagentUsageJob job :
                jobs.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc("WAITING", now)) {
            launch("child:" + job.id(), () -> poll(job.id()));
        }
    }

    /** 종료 직후 재기동된 경우에도 시작 사건을 다시 훑어 누락 작업을 만든다. */
    void discover(Instant now) {
        for (ExecutionEvent start :
                events.findUnscheduledChildren(now.minus(Duration.ofHours(24)), PageRequest.of(0, 20))) {
            transaction.executeWithoutResult(status -> {
                AgentExecution parent = executions.lockById(start.executionId()).orElse(null);
                if (parent == null || parent.finishedAt() == null) {
                    return;
                }
                if (jobs.existsByProfileNameAndChildSessionId(parent.profileName(), start.hermesSessionId())) {
                    return;
                }
                Agent agent = agents.findById(parent.agentId()).orElse(null);
                SubagentUsageJob job =
                        SubagentUsageJob.create(parent, start, agent == null ? "" : agent.apiBaseUrl(), now);
                if (agent == null || agent.isDeleted()) {
                    job.expire("AGENT_MISSING");
                } else if (!parent.profileName().equals(agent.hermesProfile())) {
                    job.expire("PROFILE_CHANGED");
                }
                // 처리할 수 없는 시작 사건도 소비해 뒤의 정상 작업 발견을 막지 않는다.
                jobs.save(job);
            });
        }
    }

    private void launch(String key, Runnable action) {
        if (!active.add(key)) {
            return;
        }
        if (!slots.tryAcquire()) {
            active.remove(key);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.warn("실행 정보 보완을 마치지 못했다 작업={}", key);
            } finally {
                active.remove(key);
                slots.release();
            }
        });
    }

    void poll(Long jobId) {
        SubagentUsageJob job = jobs.findById(jobId).orElse(null);
        if (job == null || !"WAITING".equals(job.status())) {
            return;
        }
        Instant now = clock.instant();
        SubagentSessionUsage usage = job.expired(now)
                ? null
                : hermes.readSubagentUsage(job.apiBaseUrl(), job.profileName(), job.childSessionId());
        transaction.executeWithoutResult(status -> {
            AgentExecution parent = executions.lockById(job.executionId()).orElse(null);
            SubagentUsageJob current = jobs.findById(jobId).orElse(null);
            if (parent == null || current == null || !"WAITING".equals(current.status())) {
                return;
            }
            if (isFinalChild(current, usage)) {
                // 완료 사건은 표시용이다. 부모 스트림으로 이미 왔으면 다시 만들지 않는다.
                if (!events.existsByExecutionIdAndHermesSessionIdAndEventType(
                        parent.id(), current.childSessionId(), ExecutionEventType.SUBAGENT_COMPLETED)) {
                    saveCompletion(parent, current, usage, now);
                }
                String reason = unpricedReason(usage);
                ExecutionCost cost = ExecutionCost.unknown();
                if (reason == null) {
                    cost = estimator.estimate(
                            usage.provider(),
                            usage.model(),
                            new TokenUsage(
                                    usage.inclusiveInputTokens(), usage.cacheReadTokens(), usage.outputTokens(), null),
                            parent.costMode());
                    if (!cost.isKnown()) {
                        cost = ExecutionCost.unknown();
                        reason = "PRICE_UNKNOWN";
                    }
                }
                current.record(usage, cost, reason, now);
            } else if (current.expired(now)) {
                current.expire();
            } else {
                current.retry(now);
            }
            jobs.save(current);
        });
    }

    private void saveCompletion(AgentExecution parent, SubagentUsageJob job, SubagentSessionUsage usage, Instant now) {
        List<ExecutionEvent> starts = events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(parent.id()));
        ExecutionEvent start = starts.stream()
                .filter(event -> event.eventType() == ExecutionEventType.SUBAGENT_STARTED
                        && job.childSessionId().equals(event.hermesSessionId()))
                .findFirst()
                .orElse(null);
        events.save(ExecutionEvent.builder()
                .executionId(parent.id())
                .sequence(events.lastSequence(parent.id()) + 1)
                .eventType(ExecutionEventType.SUBAGENT_COMPLETED)
                .subagentName(start == null ? null : start.subagentName())
                .hermesSessionId(job.childSessionId())
                .model(usage.model())
                .inputTokens(usage.inclusiveInputTokens())
                .outputTokens(usage.outputTokens())
                .durationMs(usage.durationMs())
                .failed(null)
                .detail(start == null ? null : start.detail())
                .occurredAt(now)
                .build());
    }

    /** 환산을 시도하기 전에 알 수 있는 금액 미확인 까닭이다. 환산할 수 있으면 null 이다. */
    private static String unpricedReason(SubagentSessionUsage usage) {
        if (usage.provider() == null || usage.provider().isBlank()) {
            return "PROVIDER_UNKNOWN";
        }
        if (usage.inclusiveInputTokens() == null || usage.outputTokens() == null) {
            return "USAGE_UNKNOWN";
        }
        return null;
    }

    static boolean isFinalChild(SubagentUsageJob job, SubagentSessionUsage usage) {
        return usage != null
                && "subagent".equals(usage.source())
                && job.childSessionId().equals(usage.id())
                && usage.endedAt() != null;
    }

    /** 읽을 수 없는 최신 profile이 다음 실행의 기본 강도 보완까지 막지 않게 page 안에서 차례로 읽는다. */
    void supplementDefaults(List<AgentExecution> executions) {
        for (AgentExecution execution : executions) {
            if (supplementDefault(execution.id(), execution.profileName())) {
                return;
            }
        }
    }

    boolean supplementDefault(Long executionId, String profile) {
        ProfileModelDefaults value = defaults.read(profile);
        if (value == null) {
            return false;
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            AgentExecution execution = executions.lockById(executionId).orElse(null);
            if (execution != null
                    && execution.reasoningEffort() == null
                    && execution.reasoningDefaultsCheckedAt() == null) {
                if (value.reasoningEffort() != null && !value.reasoningEffort().isBlank()) {
                    execution.recordProfileReasoningDefault(value.reasoningEffort());
                }
                execution.markReasoningDefaultsChecked(clock.instant());
                executions.save(execution);
                return true;
            }
            return false;
        }));
    }
}
