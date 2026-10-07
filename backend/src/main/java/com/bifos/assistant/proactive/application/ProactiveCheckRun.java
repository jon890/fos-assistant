package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.CheckTurn;
import com.bifos.assistant.chat.application.model.CheckAnswer;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 먼저 살펴보기 한 번의 {@link CheckTurn} 이다(ADR-080, ADR-081). 살펴보기마다 새로 만들고 빈으로 두지 않는다.
 *
 * <p>입력과 지시, 대화에 남기는 글은 {@code docs/backend/proactive-check.md} 의 「실행에 싣는 것」, 「Control Plane 지시」,
 * 「대화에 남는 것」 이 갖는다. 결과와 셈은 이 객체가 들고 있다가 {@link #record} 로 살펴보기 줄에 한 번 적는다. 스트림 스레드와
 * turn 스레드가 같은 엔티티를 함께 고치지 않게 하기 위해서다.
 *
 * <p>시간과 도구 호출 상한도 여기서 지킨다. 뜻은 문서의 「상한」 이 갖는다. 상한에 닿으면 멈춘 까닭을 정하고 가상 스레드에서
 * {@link ChatService#stop} 을 부른다. 멈추기가 실패하면 까닭을 되돌리고 {@link #STOP_ATTEMPTS} 번까지 다시 시도한다.
 * {@link #close} 뒤에는 까닭을 정하지 않고 멈추기를 부르지 않는다.
 */
public class ProactiveCheckRun implements CheckTurn {

    static final String PREAMBLE = ProactiveCheckInstructions.PREAMBLE;
    static final String READ_ONLY_RULE = ProactiveCheckInstructions.READ_ONLY_RULE;
    static final String WRITES_RULE = ProactiveCheckInstructions.WRITES_RULE;
    static final String DELEGATE_RULE = ProactiveCheckInstructions.DELEGATE_RULE;
    static final String DIRECT_RULE = ProactiveCheckInstructions.DIRECT_RULE;
    static final String COMMON_RULES = ProactiveCheckInstructions.COMMON_RULES;

    /** 경계 줄과 연결 줄을 골라 지시를 만든다. */
    static String instructions(boolean writesAllowed, boolean directConnectors) {
        return ProactiveCheckInstructions.instructions(writesAllowed, directConnectors);
    }

    static final String START_NOTICE = "먼저 살펴보기를 시작했어요";
    static final String NOTHING_NEW_NOTICE = "살펴봤지만 새로 알릴 것이 없어요";
    static final String INVALID_RESULT_NOTICE = "살펴봤지만 결과 형식이 맞지 않아 정리하지 못했어요. 다시 눌러 주세요";
    /** 답이 비어 결과 블록을 읽지 못했을 때의 알림 줄이다. 형식 탓으로 안내하지 않는다. */
    static final String EMPTY_ANSWER_NOTICE = "살펴봤지만 답을 받지 못했어요. 다시 눌러 주세요";

    static final String STOPPED_NOTICE = "살펴보기를 멈췄어요";
    static final String TIME_LIMIT_NOTICE = "시간 한도에 닿아 살펴보기를 멈췄어요";
    static final String TOOL_LIMIT_NOTICE = "도구 호출 한도에 닿아 살펴보기를 멈췄어요";

    /** 시간 상한으로 멈췄을 때 살펴보기 줄의 {@code error_code} 다. */
    static final String TIME_LIMIT = "CHECK_TIME_LIMIT";

    /** 도구 호출 상한으로 멈췄을 때 살펴보기 줄의 {@code error_code} 다. */
    static final String TOOL_LIMIT = "CHECK_TOOL_LIMIT";

    /** 상한에 닿은 살펴보기에 멈추기를 부르는 최대 횟수다. 처음 한 번과 다시 시도를 모두 센다. */
    static final int STOP_ATTEMPTS = 3;

    /** 멈추기가 실패한 뒤 다시 시도하기까지 기다리는 시간이다. */
    static final Duration STOP_RETRY_INTERVAL = Duration.ofSeconds(1);

    static final String OPENING = "먼저 살펴보기를 시작한다. `skill_view(name=\"proactive-check\")` 로 지침을 읽고 그 절차대로 살펴본다.";
    static final String NO_RECENT_FINDINGS = "최근에 알린 발견이 없다.";
    static final String NO_RECENT_PROBLEMS = "최근에 받아들인 문제 후보가 없다.";

    private final ProactiveCheck check;
    private final boolean renewSession;
    private final boolean directConnectors;
    private final Deps deps;
    private final ProactiveCheckInput checkInput;
    private final ProactiveCheckResults results;
    private final ProactiveCheckLimits limits;

    private volatile String input;
    /** 멈춤 알림 줄의 글을 내줬다. 그 줄은 부르는 쪽이 저장했다. */
    private volatile boolean stopped;

    /**
     * 살펴보기 한 번이 쓰는 저장소와 부품이다. {@code ProactiveCheckService} 가 자기 빈으로 채워 넘긴다.
     *
     * @param executions 지난 살펴보기의 루트 실행 줄을 읽어 Memory 문맥 지문을 견준다
     * @param chat 상한에 닿은 turn 을 멈춘다
     * @param followUps 문제 후보가 이미 챙기는 할 일과 같은지 본다
     */
    record Deps(
            ProactiveCheckProperties properties,
            ProactiveCheckRepository checks,
            ProactiveCheckFindingRepository findings,
            ProactiveCheckProblemRepository problems,
            FollowUpService followUps,
            ChatMessageRepository messages,
            AgentExecutionRepository executions,
            ContextAssembler contextAssembler,
            CheckResultParser parser,
            CheckAnswerRenderer renderer,
            CheckReportFactory reportFactory,
            ChatService chat,
            Clock clock) {}

    /**
     * @param check 이미 저장한 {@code RUNNING} 살펴보기 줄
     * @param renewSession 이번 살펴보기를 새 session 으로 보낼지
     * @param directConnectors 시작할 때 그 에이전트에 붙은 연결이 있었는지. 있으면 지시가 연결한 서비스의 도구를 직접 부르게 한다
     */
    ProactiveCheckRun(
            CurrentUser owner,
            Long agentId,
            ProactiveCheck check,
            boolean renewSession,
            boolean directConnectors,
            Deps deps) {
        this.check = check;
        this.renewSession = renewSession;
        this.directConnectors = directConnectors;
        this.deps = deps;
        this.checkInput = new ProactiveCheckInput(owner, agentId, check, deps);
        this.results = new ProactiveCheckResults(owner, check, deps);
        this.limits = new ProactiveCheckLimits(owner, check, deps);
    }

    /** 시작할 때 옮겨 적은 쓰기 허용 값과 붙은 연결 유무로 지시를 고른다. 에이전트 칸과 바인딩을 다시 읽지 않는다. */
    @Override
    public String instructions() {
        return instructions(check.writesAllowed(), directConnectors);
    }

    /** 처음 부를 때 시각과 DB 를 읽어 만든다. 다시 불리면 처음 만든 값을 돌려준다. */
    @Override
    public synchronized String input() {
        if (input == null) {
            input = checkInput.buildInput(deps.clock().instant());
        }
        return input;
    }

    @Override
    public String startNotice() {
        return START_NOTICE;
    }

    @Override
    public boolean notifyStart() {
        return check.trigger() == CheckTrigger.MANUAL;
    }

    @Override
    public boolean renewSession() {
        return renewSession;
    }

    /** 루트 실행을 살펴보기 줄에 적은 뒤 {@code max-duration} 이 지나면 깨는 시간 상한 스레드를 띄운다. */
    @Override
    public void started(Long executionId, String hermesRootSessionId) {
        limits.started(executionId, hermesRootSessionId);
    }

    /** 스트림을 읽는 스레드에서 불린다. 센 값이 {@code max-tool-calls} 를 넘는 첫 순간 멈춘다. */
    @Override
    public void toolStarted(Long executionId) {
        limits.toolStarted(executionId);
    }

    /**
     * 자동 실행으로 시작해 답, 보고, 발견, 알림 줄을 남기지 않는 살펴보기인가. 문제 후보만 남겨 다시 가치 평가와 행동 정책을 거치게
     * 한다.
     */
    @Override
    public boolean silent() {
        return results.silent();
    }

    /**
     * 결과 블록을 읽고 발견을 검사해 대화에 남길 글을 정한다. 발견은 들고 있다가 답 메시지를 저장한 뒤 {@link #saveFindings} 가
     * 저장하고, 결과와 셈은 {@link #record} 가 적는다. 답을 저장하지 못했는데 발견이 남으면 사용자가 보지 못한 발견이 다음 살펴보기에서
     * 이미 알린 것으로 내려가기 때문이다.
     * NOTHING_NEW 여도 질문이나 읽지 못한 출처가 있으면 그린다.
     *
     * <p>블록을 읽지 못하면 까닭과 답의 길이만 로그에 남긴다. 답은 개인 맥락을 담을 수 있어 본문을 남기지 않는다.
     */
    @Override
    public CheckAnswer answer(Long executionId, String output) {
        return results.answer(executionId, output);
    }

    /** 멈춘 까닭에 맞는 알림 줄의 글이다. 부르면 그 줄을 저장한 것으로 본다. */
    @Override
    public String stoppedNotice() {
        stopped = true;
        String reason = limits.stopReason.get();
        if (TIME_LIMIT.equals(reason)) {
            return TIME_LIMIT_NOTICE;
        }
        if (TOOL_LIMIT.equals(reason)) {
            return TOOL_LIMIT_NOTICE;
        }
        return STOPPED_NOTICE;
    }

    /** 상한으로 멈췄으면 거짓이다. Control Plane 이 멈춘 것이라 대기 메시지를 그대로 보낸다. */
    @Override
    public boolean holdPendingOnStop() {
        return limits.stopReason.get() == null;
    }

    /** 시간 상한 스레드를 깨워 끝내고, 그 뒤로는 상한에 닿아도 멈추기를 부르지 않는다. 줄을 적기 전에 부른다. */
    public void close() {
        limits.close();
    }

    /** 상한에 닿아 멈춘 까닭이 남아 있는지다. 멈추기를 부르는 중이거나 멈췄으면 참이고, 멈추기가 실패했으면 거짓이다. */
    boolean limitStopped() {
        return limits.stopReason.get() != null;
    }

    /** 멈춤 알림 줄의 글을 이미 내줬는지다. 냈으면 그 줄은 대화에 저장됐다. */
    boolean stoppedNoticeSaved() {
        return stopped;
    }

    /** 이 turn 의 살펴보기 줄. {@link #record} 뒤에는 끝난 상태를 담는다. */
    ProactiveCheck check() {
        return check;
    }

    /** 살펴보기 turn 의 실행 줄. 실행 줄을 만들기 전에 끝났으면 null 이다. */
    Long rootExecutionId() {
        return check.rootExecutionId();
    }

    /**
     * turn 이 끝난 뒤 들고 있던 결과와 셈을 살펴보기 줄에 적는다. 멈춤 알림 줄을 냈으면 {@code STOPPED} 와 멈춘 까닭, 아니면
     * {@code SUCCEEDED} 다. 사용자가 멈췄으면 까닭은 비어 있다.
     *
     * @param delegations 그 트리에서 맡긴 위임 자식 수
     */
    void record(int delegations) {
        Instant now = deps.clock().instant();
        if (stopped || results.outcome == null) {
            check.stop(limits.stopReason.get(), limits.toolCalls.get(), delegations, now);
        } else if (results.outcome == CheckOutcome.INVALID_RESULT) {
            check.succeedInvalid(results.invalidReason, limits.toolCalls.get(), delegations, now);
        } else {
            TreeTokens tokens = treeTokens();
            check.succeed(
                    results.outcome,
                    results.newFindings,
                    results.referenceFindings,
                    silent() ? null : results.pendingReport,
                    limits.toolCalls.get(),
                    delegations,
                    tokens.input(),
                    tokens.cachedInput(),
                    tokens.output(),
                    now);
        }
        deps.checks().save(check);
    }

    /**
     * 들고 있던 발견과 문제 후보를 저장한다. turn 이 답 메시지를 저장하고 돌아왔을 때만 부른다. 없으면 아무것도 하지 않는다. 사용자가 보지 못한
     * 발견을 근거로 한 후보가 다음 살펴보기에서 중복으로 걸리지 않게 하기 위해 발견과 같은 자리에서 저장한다.
     */
    void saveFindings() {
        results.saveFindings();
    }

    /** turn 이 예외로 끝났을 때 살펴보기 줄을 {@code FAILED} 와 그 오류 코드로 적는다. */
    void recordFailure(String errorCode, int delegations) {
        check.fail(errorCode, limits.toolCalls.get(), delegations, deps.clock().instant());
        deps.checks().save(check);
    }

    /** 루트와 자식 실행의 토큰을 모두 더한다. 아직 적히지 않은 토큰은 0으로 센다. */
    private TreeTokens treeTokens() {
        Long rootId = check.rootExecutionId();
        if (rootId == null) {
            return TreeTokens.ZERO;
        }
        List<AgentExecution> tree = new ArrayList<>(deps.executions().findByRootExecutionId(rootId));
        deps.executions().findById(rootId).ifPresent(tree::add);
        long input = tree.stream().mapToLong(each -> orZero(each.inputTokens())).sum();
        long cachedInput = tree.stream()
                .mapToLong(each -> orZero(each.cachedInputTokens()))
                .sum();
        long output =
                tree.stream().mapToLong(each -> orZero(each.outputTokens())).sum();
        return new TreeTokens(input, cachedInput, output);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private record TreeTokens(long input, long cachedInput, long output) {
        private static final TreeTokens ZERO = new TreeTokens(0, 0, 0);
    }
}
