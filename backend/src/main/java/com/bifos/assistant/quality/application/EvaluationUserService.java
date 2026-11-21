package com.bifos.assistant.quality.application;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.AgentPendingToolsets;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.AgentToolService;
import com.bifos.assistant.agent.application.dto.AgentToolView;
import com.bifos.assistant.agent.config.PeopleProperties;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.people.application.PersonRegistrar;
import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.quality.application.dto.EvaluationUserStatus;
import com.bifos.assistant.quality.application.dto.EvaluationUserStatus.Missing;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.user.application.UserProvisioningService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배포 뒤 확인이 쓰는 평가 전용 사용자를 준비하고 상태를 읽는다(docs/features/agent-quality/design.md 의 「평가 전용 사용자」).
 *
 * <p>준비는 다시 불러도 빠진 것만 채운다. 기본 비서의 도구(첫 로그인 기본 도구와 그림·사진 도구)는 기본 도구 대기 목록
 * ({@link AgentPendingToolsets})에 올려 행 잠금 안에서 실행 공간 안으로만 켠다. 첫 로그인의 기본 도구 켜기와 같은 목록을 따로
 * 읽고 통째로 쓰면 나중에 쓴 쪽이 다른 쪽 이름을 지운다. 셸·파일 도구를 실행 공간 밖으로 켜는 쓰기는 다른 profile 의 파일에
 * 닿을 수 있어 쓰지 않는다. 실행 공간이 없으면 준비는 끝내고 「정책 등록 필요」 로 알린다.
 *
 * <p>준비 전체에 트랜잭션을 걸지 않는다. Hermes 를 부르는 단계는 트랜잭션 밖에서 돌고, 사용자 행을 만들고 표시하는 두 단계만 한
 * 트랜잭션으로 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationUserService {

    static final String EMAIL = DeployCheckItems.EMAIL;

    static final String DELEGATE_AGENT_NAME = DeployCheckItems.DELEGATE_AGENT_NAME;

    /** 첫 로그인 기본 도구에 더해 실행 공간 안에서만 켜는 확인용 도구다. */
    static final List<String> SANDBOX_CHECK_TOOLSETS = List.of(AgentToolPolicy.IMAGE_GEN, AgentToolPolicy.VISION);

    private final PersonRegistrar registrar;
    private final SignInPolicy signInPolicy;
    private final UserProvisioningService provisioning;
    private final AppUserRepository users;
    private final AgentService agents;
    private final AgentLifecycleService agentLifecycle;
    private final AgentPendingToolsets pendingToolsets;
    private final PeopleProperties peopleProperties;
    private final AgentToolService agentTools;
    private final MemoryService memories;
    private final TransactionTemplate transactions;
    private final Clock clock;


    /**
     * 평가 전용 사용자를 준비한다. 이미 있으면 요청의 profile 은 무시하고 처음 만든 profile 로 빠진 것만 채운다.
     *
     * @param admin 요청한 관리자. 관리자인지는 부르는 쪽이 먼저 확인한다
     * @param hermesProfile 처음 만들 때 쓸 profile 이름. 운영이 실행 공간과 첨부 정책에 등록한 이름이다
     */
    public EvaluationUserStatus prepare(CurrentUser admin, String hermesProfile) {
        Optional<AppUser> existing = find(admin.groupId());
        AppUser user = existing.orElseGet(() -> create(hermesProfile));
        CurrentUser evaluation = asCurrentUser(user);
        Optional<Agent> primary = primaryAgent(evaluation);
        if (existing.isPresent() && primary.isPresent()) {
            // 처음 만들 때는 create 가 목록에 올렸고, 첫 로그인의 백그라운드 작업이 기본 도구를 쓴 다음 차례에 켠다.
            pendingToolsets.add(primary.get().id(), expectedToolsets());
            pendingToolsets.attempt(primary.get().id());
        }
        if (delegateAgent(evaluation).isEmpty()) {
            agentLifecycle.create(evaluation, DELEGATE_AGENT_NAME, AgentVisibility.PRIVATE);
        }
        if (!hasMemory(evaluation)) {
            memories.create(evaluation, MemoryScope.USER, DeployCheckItems.MEMORY_TITLE, DeployCheckItems.MEMORY_CONTENT, true);
        }
        return status(admin);
    }


    /** 그룹의 평가 전용 사용자가 준비됐는지 읽는다. */
    public EvaluationUserStatus status(CurrentUser admin) {
        Optional<AppUser> found = find(admin.groupId());
        if (found.isEmpty()) {
            return new EvaluationUserStatus(null, List.of(Missing.USER.name()));
        }
        CurrentUser evaluation = asCurrentUser(found.get());
        List<String> missing = new ArrayList<>();
        Optional<Agent> primary = primaryAgent(evaluation);
        if (primary.isEmpty()) {
            missing.add(Missing.PRIMARY_AGENT.name());
        } else {
            toolsMissing(admin, primary.get()).ifPresent(reason -> missing.add(reason.name()));
        }
        if (delegateAgent(evaluation).isEmpty()) {
            missing.add(Missing.DELEGATE_AGENT.name());
        }
        if (!hasMemory(evaluation)) {
            missing.add(Missing.MEMORY.name());
        }
        String profile = primary.map(Agent::hermesProfile).orElse(null);
        return new EvaluationUserStatus(profile, missing);
    }


    /** 평가 전용 사용자의 행이다. 실행기가 이 사용자로 돈다. */
    public Optional<AppUser> find(Long groupId) {
        return users.findFirstByGroupIdAndEvaluationTrueOrderByIdAsc(groupId);
    }


    /**
     * 허용 목록 행과 Hermes profile 을 만든 뒤(트랜잭션 밖), 사용자 행과 첫 에이전트를 만들고 평가 전용으로 표시한다(한 트랜잭션).
     * 허용 목록 행이 이미 있으면 그대로 쓴다.
     *
     * <p>켤 도구도 같은 트랜잭션에서 기본 비서의 대기 목록에 올린다. 첫 로그인의 백그라운드 작업은 커밋 뒤에 시작해 기본 도구를
     * 쓴 다음 이 목록을 행 잠금 안에서 켜므로, 두 쓰기가 겹치지 않는다.
     */
    private AppUser create(String hermesProfile) {
        if (signInPolicy.admit(EMAIL).isEmpty()) {
            registrar.register(EMAIL, DeployCheckItems.DISPLAY_NAME, hermesProfile);
        }
        return transactions.execute(status -> {
            AppUser created = provisioning.resolve(EMAIL, DeployCheckItems.DISPLAY_NAME);
            users.markEvaluation(created.id(), clock.instant());
            primaryAgent(asCurrentUser(created)).ifPresent(primary -> pendingToolsets.add(primary.id(), expectedToolsets()));
            return users.findById(created.id()).orElseThrow();
        });
    }


    /** 기본 비서에 켜져 있어야 하는 도구다. 첫 로그인 기본 도구와 확인용 그림·사진 도구다. */
    private List<String> expectedToolsets() {
        LinkedHashSet<String> names = new LinkedHashSet<>(peopleProperties.defaultToolsets());
        names.addAll(SANDBOX_CHECK_TOOLSETS);
        return List.copyOf(names);
    }


    /**
     * 켜져 있어야 할 도구가 모두 켜졌는지 본다. 셸·파일 도구도 본다. 겹친 쓰기로 빠졌으면 준비를 다시 불러 채운다.
     *
     * @return 빠졌으면 그 까닭. Hermes 가 목록을 돌려주지 않으면 {@link Missing#TOOLS_UNREADABLE} 이다
     */
    private Optional<Missing> toolsMissing(CurrentUser admin, Agent primary) {
        Set<String> enabled;
        try {
            enabled = agentTools.readAsAdmin(admin, primary.code()).toolsets().stream()
                .filter(AgentToolView::enabled)
                .map(AgentToolView::name)
                .collect(Collectors.toSet());
        } catch (ApiException failure) {
            log.warn("평가 사용자의 도구 목록을 읽지 못했다 code={} profile={}", failure.code(), primary.hermesProfile());
            return Optional.of(Missing.TOOLS_UNREADABLE);
        }
        return enabled.containsAll(expectedToolsets()) ? Optional.empty() : Optional.of(Missing.POLICY_REGISTRATION_REQUIRED);
    }


    /** 첫 에이전트다. 첫 로그인이 만든 기본 비서이고 그 code 는 profile 이름이다. */
    Optional<Agent> primaryAgent(CurrentUser evaluation) {
        return owned(evaluation).stream()
            .filter(agent -> AgentIntroduction.PRIMARY_NAME.equals(agent.name()))
            .filter(agent -> agent.code().equals(agent.hermesProfile()))
            .findFirst();
    }


    Optional<Agent> delegateAgent(CurrentUser evaluation) {
        return owned(evaluation).stream().filter(agent -> DELEGATE_AGENT_NAME.equals(agent.name())).findFirst();
    }


    private List<Agent> owned(CurrentUser evaluation) {
        return agents.readableBy(evaluation).stream()
            .filter(agent -> evaluation.id().equals(agent.ownerUserId()))
            .filter(agent -> !agent.isDeleted())
            .toList();
    }


    private boolean hasMemory(CurrentUser evaluation) {
        return memories.readableBy(evaluation).stream().anyMatch(memory -> DeployCheckItems.MEMORY_TITLE.equals(memory.title()));
    }


    static CurrentUser asCurrentUser(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
