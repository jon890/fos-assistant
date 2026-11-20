package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.dto.PendingToolsetOutcome;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.user.application.port.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기본 비서에 켜 주기로 했지만 아직 켜지 못한 기본 도구를 요청 밖에서 다시 켠다(ADR-20261119 / image-gen-defaults).
 *
 * <p>기다리는 이름은 {@code agent.pending_default_toolsets} 에 있다. 이미 있던 기본 비서는 마이그레이션이 한 번, 새 기본 비서는
 * 첫 로그인이 적는다. 켜졌거나 관리자가 그 도구를 끄면 비운다. 비운 뒤에는 배포와 재시작이 다시 켜지 않는다.
 *
 * <p>실행 공간 정책에 profile 을 등록하는 일은 Control Plane 이 하지 못한다. 그 단계가 남으면 주인 그룹의 관리자에게 알림을 한 번
 * 남기고, 등록이 끝난 뒤의 차례에 스스로 켠다. 에이전트 하나는 행을 잠근 한 트랜잭션 안에서 본다. 사람의 도구 저장과 겹치지 않게
 * 하려는 것이다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentPendingToolsets {

    /** 한 번에 읽는 에이전트 번호 수다. 한 차례는 쪽을 넘기며 모두 본다. */
    static final int BATCH = 50;
    /** Hermes 가 답하지 않는 실패가 이만큼 이어지면 관리자에게 알린다. 배포 중의 한두 번은 알리지 않는다. */
    static final int FAILURE_NOTICE_STREAK = 3;
    static final String TITLE = "기본 도구를 켜지 못했어요";

    private final AgentRepository agents;
    private final AgentDefaultToolsets defaults;
    private final AppUserRepository users;
    private final SignInRevocation access;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;

    /** 에이전트마다 이어진 실패 수다. 메모리에만 둔다. */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();


    /** 운영은 5분마다 돈다. 검사에서는 {@code -} 로 끄고 {@link #attemptAll} 을 직접 부른다. */
    @Scheduled(cron = "${assistant.agents.pending-toolsets-cron}")
    public void runScheduled() {
        attemptAll();
    }


    /**
     * 기다리는 도구가 있는 에이전트를 차례로 켜 본다.
     *
     * <p>마지막으로 본 번호 뒤부터 다음 쪽을 읽는다. 켜지 못해 남는 에이전트가 쪽 크기를 넘어도 번호가 큰 에이전트까지 매 차례 본다.
     */
    public void attemptAll() {
        long afterId = 0L;
        List<Long> page;
        do {
            page = agents.findIdsWithPendingDefaultToolsets(afterId, PageRequest.of(0, BATCH));
            for (Long agentId : page) {
                attempt(agentId);
            }
            if (!page.isEmpty()) {
                afterId = page.get(page.size() - 1);
            }
        } while (page.size() == BATCH);
    }


    /** 새 기본 비서에 기다릴 도구를 적는다. 부르는 쪽의 트랜잭션이 있으면 거기에 참여한다. */
    public void mark(Long agentId, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        transactions.executeWithoutResult(status -> agents.writePendingDefaultToolsets(agentId, String.join(",", names)));
    }


    /**
     * 에이전트 하나의 기다리는 도구를 켜 본다. 실패해도 예외를 올리지 않고 다음 차례에 다시 본다.
     */
    public void attempt(Long agentId) {
        try {
            transactions.executeWithoutResult(status -> attemptLocked(agentId));
            failures.remove(agentId);
        } catch (RuntimeException failure) {
            int streak = failures.merge(agentId, 1, Integer::sum);
            log.warn("기다리는 기본 도구를 켜지 못했다. 다음 차례에 다시 본다 agentId={} streak={}", agentId, streak, failure);
            if (streak == FAILURE_NOTICE_STREAK) {
                noticeFailure(agentId);
            }
        }
    }


    /**
     * 사람이 도구를 저장한 뒤에 부른다. 켜진 이름과 관리자가 끈 이름을 기다리는 목록에서 뺀다(스펙 generated-image DECISION23).
     *
     * <p>기다리는 도구는 관리자 등급이라 켜짐이 바뀌었으면 관리자가 정한 것이다. 꺼진 채 그대로인 이름은 계속 기다린다.
     *
     * @param before 저장 전에 켜져 있던 도구
     * @param after 저장 뒤에 켜진 도구
     */
    public void settle(Agent agent, List<String> before, List<String> after) {
        List<String> names = agent.pendingToolsets();
        List<String> remaining = names.stream()
            .filter(name -> !before.contains(name) && !after.contains(name))
            .toList();
        if (remaining.size() == names.size()) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            agents.writePendingDefaultToolsets(agent.id(), remaining.isEmpty() ? null : String.join(",", remaining));
            if (remaining.isEmpty()) {
                resolveNotices(agent);
            }
        });
    }


    private void attemptLocked(Long agentId) {
        Agent agent = agents.findByIdForUpdate(agentId).orElse(null);
        if (agent == null) {
            return;
        }
        List<String> names = agent.pendingToolsets();
        if (names.isEmpty()) {
            return;
        }
        if (agent.isDeleted()) {
            agents.writePendingDefaultToolsets(agentId, null);
            return;
        }
        PendingToolsetOutcome outcome = defaults.enablePending(agent, names);
        switch (outcome) {
            case ENABLED, NOT_ALLOWED -> {
                log.info("기다리던 기본 도구를 끝냈다 agent={} outcome={} names={}", agent.code(), outcome, names);
                agents.writePendingDefaultToolsets(agentId, null);
                resolveNotices(agent);
            }
            case SANDBOX_UNAVAILABLE -> notice(agent, names, Cause.SANDBOX);
            case SKILL_REQUESTS_SECRETS -> notice(agent, names, Cause.SKILL);
        }
    }


    /** 이어진 실패를 새 트랜잭션에서 알린다. 알리지 못해도 다음 차례는 돈다. */
    private void noticeFailure(Long agentId) {
        try {
            transactions.executeWithoutResult(status -> agents.findById(agentId)
                .filter(agent -> !agent.isDeleted() && !agent.pendingToolsets().isEmpty())
                .ifPresent(agent -> notice(agent, agent.pendingToolsets(), Cause.FAILED)));
        } catch (RuntimeException failure) {
            log.warn("기본 도구 실패 알림을 남기지 못했다 agentId={}", agentId, failure);
        }
    }


    /** 주인 그룹의 차단되지 않은 관리자마다 그 까닭의 알림을 한 번만 남긴다. */
    private void notice(Agent agent, List<String> names, Cause cause) {
        AppUser owner = owner(agent);
        // 평가 전용 사용자의 에이전트는 관리자 알림에 드러내지 않는다(docs/features/agent-quality/design.md 의 「숨기는 곳」).
        if (owner == null || owner.evaluation()) {
            return;
        }
        String body = owner.displayName() + " 님의 기본 비서에 " + labels(names) + " 를 켜지 못했어요. " + cause.reason;
        for (AppUser admin : admins(owner)) {
            notifications.notifyOnce(
                admin.id(), NotificationKind.DEFAULT_TOOLS_PENDING, TITLE, body, null, source(agent, cause));
        }
    }


    /** 켜졌거나 더 기다리지 않게 된 에이전트의 알림을 읽음으로 바꾼다. */
    private void resolveNotices(Agent agent) {
        AppUser owner = owner(agent);
        // 평가 전용 사용자의 에이전트는 관리자 알림에 드러내지 않는다(docs/features/agent-quality/design.md 의 「숨기는 곳」).
        if (owner == null || owner.evaluation()) {
            return;
        }
        for (AppUser admin : admins(owner)) {
            for (Cause cause : Cause.values()) {
                notifications.resolve(admin.id(), NotificationKind.DEFAULT_TOOLS_PENDING, source(agent, cause));
            }
        }
    }


    private AppUser owner(Agent agent) {
        return agent.ownerUserId() == null ? null : users.findById(agent.ownerUserId()).orElse(null);
    }


    private List<AppUser> admins(AppUser owner) {
        return users.findByGroupIdAndRole(owner.groupId(), UserRole.ADMIN).stream()
            .filter(admin -> !access.revoked(admin.email()))
            .toList();
    }


    /** 에이전트와 까닭으로 정한 알림의 원인 식별자다. 같은 까닭은 한 번만 알리고, 까닭이 바뀌면 다시 알린다. */
    private static UUID source(Agent agent, Cause cause) {
        return UUID.nameUUIDFromBytes(
            ("agent-pending-toolsets:" + agent.id() + ":" + cause.name()).getBytes(StandardCharsets.UTF_8));
    }


    private static String labels(List<String> names) {
        return names.stream()
            .map(name -> AgentToolPolicy.IMAGE_GEN.equals(name) ? "「그림 만들기」" : "「" + name + "」")
            .collect(Collectors.joining(", "));
    }


    /** 켜지 못한 까닭과 관리자에게 보일 글이다. 이름은 알림의 원인 식별자에 들어가므로 바꾸지 않는다. */
    private enum Cause {
        SANDBOX("실행 공간이 아직 준비되지 않았어요. 운영에서 그 profile 을 실행 공간 정책에 등록하면 스스로 켜요."),
        SKILL("비밀값을 요청하는 올린 스킬이 있어요. 그 스킬을 지우면 스스로 켜요."),
        FAILED("Hermes 가 도구 설정을 받지 않았어요. Hermes 와 대시보드 plugin 의 상태를 확인해 주세요. 계속 다시 시도해요.");


        private final String reason;


        Cause(String reason) {
            this.reason = reason;
        }
    }
}
