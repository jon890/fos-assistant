package com.bifos.assistant.usage.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.ProfileModelDefaultsClient;
import com.bifos.assistant.hermes.dto.ProfileModelDefaults;
import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import com.bifos.assistant.usage.domain.AgentExecution;
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

/** 대화 응답과 분리해 최종 자식 사용량과 profile 기본 강도를 보완한다. */
@Service
@Slf4j
public class SubagentUsageReconciler {
    private final AgentExecutionRepository executions;
    private final ExecutionEventRepository events;
    private final SubagentUsageJobRepository jobs;
    private final AgentService agents;
    private final HermesRunsClient hermes;
    private final ProfileModelDefaultsClient defaults;
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
            PlatformTransactionManager manager) {
        this(executions, events, jobs, agents, hermes, defaults, manager, Clock.systemUTC());
    }

    SubagentUsageReconciler(
            AgentExecutionRepository executions,
            ExecutionEventRepository events,
            SubagentUsageJobRepository jobs,
            AgentService agents,
            HermesRunsClient hermes,
            ProfileModelDefaultsClient defaults,
            PlatformTransactionManager manager,
            Clock clock) {
        this.executions = executions;
        this.events = events;
        this.jobs = jobs;
        this.agents = agents;
        this.hermes = hermes;
        this.defaults = defaults;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Scheduled(cron = "${assistant.usage.reconcile-cron:*/5 * * * * *}")
    public void reconcile() {
        Instant now = clock.instant();
        discover(now);
        for (SubagentUsageJob job :
                jobs.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc("WAITING", now)) {
            launch("child:" + job.id(), () -> poll(job.id()));
        }
        for (AgentExecution execution :
                executions.findUnknownReasoningDefaults(now.minus(Duration.ofHours(24)), PageRequest.of(0, 20))) {
            launch("profile:" + execution.id(), () -> supplementDefault(execution.id(), execution.profileName()));
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
                if (jobs.existsByExecutionIdAndChildSessionId(parent.id(), start.hermesSessionId())) {
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
            if (events.existsByExecutionIdAndHermesSessionIdAndEventType(
                    parent.id(), current.childSessionId(), ExecutionEventType.SUBAGENT_COMPLETED)) {
                current.done();
            } else if (current.expired(now)) {
                current.expire();
            } else if (isFinalChild(current, usage)) {
                List<ExecutionEvent> starts =
                        events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(parent.id()));
                ExecutionEvent start = starts.stream()
                        .filter(event -> event.eventType() == ExecutionEventType.SUBAGENT_STARTED
                                && current.childSessionId().equals(event.hermesSessionId()))
                        .findFirst()
                        .orElse(null);
                events.save(ExecutionEvent.builder()
                        .executionId(parent.id())
                        .sequence(events.lastSequence(parent.id()) + 1)
                        .eventType(ExecutionEventType.SUBAGENT_COMPLETED)
                        .subagentName(start == null ? null : start.subagentName())
                        .hermesSessionId(current.childSessionId())
                        .model(usage.model())
                        .inputTokens(usage.inclusiveInputTokens())
                        .outputTokens(usage.outputTokens())
                        .durationMs(usage.durationMs())
                        .failed(null)
                        .detail(start == null ? null : start.detail())
                        .occurredAt(now)
                        .build());
                current.done();
            } else {
                current.retry(now);
            }
            jobs.save(current);
        });
    }

    static boolean isFinalChild(SubagentUsageJob job, SubagentSessionUsage usage) {
        return usage != null
                && "subagent".equals(usage.source())
                && job.childSessionId().equals(usage.id())
                && job.parentSessionId() != null
                && job.parentSessionId().equals(usage.parentSessionId())
                && usage.endedAt() != null;
    }

    private void supplementDefault(Long executionId, String profile) {
        ProfileModelDefaults value = defaults.read(profile);
        if (value == null
                || value.reasoningEffort() == null
                || value.reasoningEffort().isBlank()) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            AgentExecution execution = executions.lockById(executionId).orElse(null);
            if (execution != null && execution.reasoningEffort() == null) {
                execution.recordProfileReasoningDefault(value.reasoningEffort());
                executions.save(execution);
            }
        });
    }
}
