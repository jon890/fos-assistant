package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.proactive.application.SurfacedProblems;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 검사에서 매일 루프가 먼저 다룰 문제를 골라 둔 상태를 만든다.
 *
 * <p>브라우저 환경에는 판단 profile 이 없어 실제 루프로는 {@code SURFACE} 판정이 생기지 않는다. 점검 대화, 살펴보기, 문제 후보,
 * 평가, 판정, {@code DECIDED} 시도 줄을 {@link SurfacedProblemSeed} 로 직접 저장하고 루프가 남기는 {@code SURFACED} 사건도 남긴다.
 */
@RestController
@RequestMapping("/api/v1/test-support/proactive-loop")
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class ProactiveLoopTestSupportController {

    private final CurrentUserProvider currentUser;
    private final AgentRepository agents;
    private final CheckConversations checkConversations;
    private final AutonomyDecisionRepository decisions;
    private final SurfacedProblems surfacedProblems;
    private final SurfacedProblemSeed seed;
    private final Clock clock;

    public ProactiveLoopTestSupportController(
            CurrentUserProvider currentUser,
            AgentRepository agents,
            CheckConversations checkConversations,
            ProactiveCheckRepository checks,
            ProactiveCheckProblemRepository problems,
            ValueEvaluationRepository evaluations,
            AutonomyDecisionRepository decisions,
            ProactiveLoopRunRepository loopRuns,
            SurfacedProblems surfacedProblems,
            Clock clock) {
        this.currentUser = currentUser;
        this.agents = agents;
        this.checkConversations = checkConversations;
        this.decisions = decisions;
        this.surfacedProblems = surfacedProblems;
        this.clock = clock;
        this.seed = new SurfacedProblemSeed(checkConversations, checks, problems, evaluations, decisions, loopRuns);
    }

    /**
     * 부르는 사용자의 점검 대화에 먼저 다룰 문제를 하나 심는다. 문제 키는 호출마다 새로 만들어 서로 겹치지 않는다.
     *
     * <p>{@code level} 은 {@code SURFACE} 나 {@code ASK_APPROVAL} 이다.
     */
    @PostMapping("/surfaced")
    public Surfaced surfaced(@RequestBody SurfacedRequest request) {
        CurrentUser user = currentUser.require();
        AutonomyLevel level = AutonomyLevel.valueOf(Objects.requireNonNull(request.level(), "level"));
        Agent agent = agents.findByCode(request.agentCode()).orElseThrow();
        UUID conversationId =
                checkConversations.findOrCreate(user, agent).conversation().publicId();
        SurfacedProblemSeed.Seeded seeded = seed.decided(
                user,
                agent,
                new SurfacedProblemSeed.Spec(
                        "browser-" + UUID.randomUUID(), request.problem(), request.action(), level, clock.instant()));
        surfacedProblems.surfaced(decisions.findById(seeded.decisionId()).orElseThrow());
        return new Surfaced(seeded.decisionId(), conversationId);
    }

    /** 심을 문제의 에이전트 코드, 문제 글, 제안한 다음 행동, 판정 수준이다. */
    public record SurfacedRequest(String agentCode, String problem, String action, String level) {}

    /** 심은 판정의 번호와 점검 대화의 공개 식별자다. */
    public record Surfaced(Long decisionId, UUID conversationId) {}
}
