package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.dto.SandboxReapplyOutcome;
import com.bifos.assistant.agent.config.AgentProperties;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동할 때 실행 공간 도구가 켜진 에이전트를 지금 목록 그대로 다시 저장한다(ADR-20261120 / no-local-shell).
 *
 * <p>plugin 이 정책 밖 profile 의 셸 저장을 막아도 이미 local 로 켜진 셸은 다음 저장까지 남는다. Control Plane 은 어느 profile 이
 * local 인지 모르므로 실행 공간 도구가 켜진 에이전트를 모두 다시 저장한다. 정책에 있으면 docker 로 바뀌고, plugin 이 정책 밖이라고
 * 답하면 그 도구를 끈다.
 *
 * <p>매 기동 돈다. 정책이 그대로면 다시 써도 결과가 같다. Hermes 가 함께 재시작되는 중이라 실패한 에이전트는 메모리에 두고 기다리는
 * 기본 도구 주기에 끝날 때까지 다시 본다. 에이전트 하나는 행을 잠근 한 트랜잭션 안에서 본다. 사람의 도구 저장과 겹치지 않게 하려는
 * 것이다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentSandboxSweep {

    /** 한 번에 읽는 에이전트 번호 수다. */
    static final int BATCH = 50;

    private final AgentRepository agents;
    private final AgentDefaultToolsets defaults;
    private final AgentProperties properties;
    private final BackgroundTasks backgroundTasks;
    private final TransactionTemplate transactions;

    /** 아직 끝내지 못한 에이전트 번호다. 메모리에만 둔다. 다음 기동은 처음부터 모두 본다. */
    private final Set<Long> unfinished = ConcurrentHashMap.newKeySet();


    /** 기동을 늦추지 않게 백그라운드에서 돈다. 검사에서는 설정으로 끄고 {@link #sweepAll} 을 직접 부른다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.sandboxSweepOnStart()) {
            backgroundTasks.start("agent-sandbox-sweep", this::sweepAll);
        }
    }


    /** 지우지 않은 에이전트를 번호 순으로 모두 본다. */
    public void sweepAll() {
        Map<SandboxReapplyOutcome, Integer> counts = new EnumMap<>(SandboxReapplyOutcome.class);
        long afterId = 0L;
        List<Long> page;
        do {
            page = agents.findLiveIdsAfter(afterId, PageRequest.of(0, BATCH));
            for (Long agentId : page) {
                sweep(agentId).ifPresent(outcome -> counts.merge(outcome, 1, Integer::sum));
            }
            if (!page.isEmpty()) {
                afterId = page.get(page.size() - 1);
            }
        } while (page.size() == BATCH);
        log.info("실행 공간 도구를 다시 저장했다 outcomes={} unfinished={}", counts, unfinished.size());
    }


    /** 끝내지 못한 에이전트만 다시 본다. 목록이 비면 아무것도 하지 않는다. */
    @Scheduled(cron = "${assistant.agents.pending-toolsets-cron}")
    public void retryUnfinished() {
        if (unfinished.isEmpty()) {
            return;
        }
        for (Long agentId : List.copyOf(unfinished)) {
            sweep(agentId);
        }
        log.info("끝내지 못한 실행 공간 도구 다시 저장을 다시 봤다 unfinished={}", unfinished.size());
    }


    /**
     * 에이전트 하나를 다시 저장한다. 실패해도 예외를 올리지 않고 끝내지 못한 목록에 둔다.
     *
     * @return 끝났으면 그 결과. 행이 없거나 지웠으면 비어 있다
     */
    Optional<SandboxReapplyOutcome> sweep(Long agentId) {
        try {
            Optional<SandboxReapplyOutcome> outcome = Optional.ofNullable(transactions.execute(status -> sweepLocked(agentId)));
            unfinished.remove(agentId);
            return outcome;
        } catch (RuntimeException failure) {
            unfinished.add(agentId);
            log.warn("실행 공간 도구를 다시 저장하지 못했다. 다음 차례에 다시 본다 agentId={}", agentId, failure);
            return Optional.empty();
        }
    }


    /** 끝내지 못한 에이전트 수다. 0 이면 실행 공간 도구가 켜진 에이전트를 모두 바로잡았다. */
    public int unfinishedCount() {
        return unfinished.size();
    }


    private SandboxReapplyOutcome sweepLocked(Long agentId) {
        Agent agent = agents.findByIdForUpdate(agentId).orElse(null);
        if (agent == null || agent.isDeleted()) {
            return null;
        }
        return defaults.reapplySandbox(agent);
    }
}
