package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnHandle;
import com.bifos.assistant.chat.application.model.OpenedCheck;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.proactive.application.model.CheckReadiness;
import com.bifos.assistant.proactive.application.model.CheckStatusView;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 살펴보기의 진입점이다(ADR-080). 진입 경로는 {@code docs/backend/proactive-check.md} 의 「진입점」 이 갖는다.
 *
 * <p>모든 경로가 먼저 요청자가 그 에이전트로 대화를 시작할 수 있는지 본다. 아니면 {@code AGENT_NOT_FOUND} 나
 * {@code AGENT_DISABLED} 이고 Hermes 를 부르지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProactiveCheckService {

    static final String FAILED_NOTICE = "살펴보기를 끝내지 못했어요. 잠시 뒤 다시 눌러 주세요";

    private final AgentService agents;
    private final ProactiveCheckReadiness readiness;
    private final CheckConversations checkConversations;
    private final ProactiveCheckRepository checks;
    private final ProactiveCheckFindingRepository findings;
    private final ProactiveCheckProperties properties;
    private final TurnCancellation turns;
    private final ChatService chat;
    private final ConversationEventHub hub;
    private final ConversationNotices notices;
    private final ChatMessageRepository messages;
    private final AgentExecutionRepository executions;
    private final ContextAssembler contextAssembler;
    private final CheckResultParser parser;
    private final CheckAnswerRenderer renderer;
    private final CheckReportFactory reportFactory;
    private final ExecutionDeliveryWriter deliveryWriter;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /**
     * 살펴보기를 할 수 있는지, 요청자의 점검 대화, 요청자의 마지막 살펴보기를 읽는다.
     *
     * <p>준비 판정이 Hermes 를 부른다. 실패하면 그 예외를 그대로 올린다. 같은 에이전트를 쓰는 다른 사용자의 점검 대화와
     * 살펴보기는 싣지 않는다.
     */
    public CheckStatusView status(CurrentUser user, String agentCode) {
        Agent agent = agents.requireStartable(user, agentCode);
        CheckReadiness checked = readiness.check(agent);
        return new CheckStatusView(
                checked,
                checkConversations
                        .find(user.id(), agent.id())
                        .map(Conversation::publicId)
                        .orElse(null),
                checks.findFirstByUserIdAndAgentIdOrderByIdDesc(user.id(), agent.id())
                        .orElse(null));
    }

    /**
     * 살펴보기를 시작하고 점검 대화의 공개 식별자를 돌려준다. 결과는 그 대화의 SSE 와 이력으로 간다.
     *
     * <p>잠금을 잡고 살펴보기 줄을 저장한 뒤 가상 스레드에서 turn 을 돌리고 곧바로 돌아온다. 막는 까닭, 사용자 자리, 대화 잠금의
     * 거절은 그 전에 예외로 나간다. 사용자 자리가 없어 거절하면 이번에 만든 점검 대화를 지운다.
     *
     * @throws ApiException {@code AGENT_NOT_FOUND}, {@code AGENT_DISABLED}, {@code PROACTIVE_CHECK_UNAVAILABLE},
     *     {@code USER_BUSY}, {@code CONVERSATION_BUSY}
     */
    public UUID start(CurrentUser user, String agentCode, CheckTrigger trigger) {
        return start(user, agentCode, trigger, ignored -> {});
    }

    /** 예약 작업이 점검 줄을 자기 실행 기록에 잇는 전용 진입점이다. */
    public UUID startScheduled(CurrentUser user, String agentCode, Consumer<ProactiveCheck> beforeRun) {
        return start(user, agentCode, CheckTrigger.SCHEDULED, beforeRun);
    }

    /**
     * 예약 작업이 자기 {@code task_run}에 정확히 그 점검 줄을 잇도록, Hermes 호출 전에 저장한 점검 줄을 넘긴다.
     * 기존 UUID 반환 경로는 위의 메서드가 유지한다.
     */
    public UUID start(CurrentUser user, String agentCode, CheckTrigger trigger, Consumer<ProactiveCheck> beforeRun) {
        Agent agent = agents.requireStartable(user, agentCode);
        if (!readiness.check(agent).available()) {
            throw new ApiException(
                    ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, "this agent cannot run a proactive check now");
        }
        OpenedCheck opened = checkConversations.findOrCreate(user, agent);
        Conversation conversation = opened.conversation();
        if (trigger == CheckTrigger.SCHEDULED && hasUnreadReport(conversation.id())) {
            ProactiveCheck skipped = ProactiveCheck.started(
                    user.id(),
                    agent.id(),
                    conversation.id(),
                    trigger,
                    agent.proactiveCheckWritesAllowed(),
                    clock.instant());
            skipped.skip(CheckSkippedReason.UNREAD_REPORT, clock.instant());
            saveLinked(skipped, beforeRun);
            return conversation.publicId();
        }
        TurnHandle handle;
        try {
            handle = trigger == CheckTrigger.SCHEDULED
                    ? turns.openBackground(user.id(), conversation.id())
                    : turns.open(user.id(), conversation.id());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.USER_BUSY && opened.created()) {
                checkConversations.deleteCreated(conversation.id());
            }
            throw ex;
        }
        ProactiveCheck check = null;
        try {
            // 그 에이전트의 쓰기 허용 값을 지금 옮겨 적는다. 이 살펴보기의 경계는 옮겨 적은 값이 정한다(ADR-082).
            ProactiveCheck started = ProactiveCheck.started(
                    user.id(),
                    agent.id(),
                    conversation.id(),
                    trigger,
                    agent.proactiveCheckWritesAllowed(),
                    clock.instant());
            check = saveLinked(started, beforeRun);
            ProactiveCheckRun run = new ProactiveCheckRun(user, agent.id(), check, renewsSession(conversation), deps());
            Thread.ofVirtual()
                    .name("proactive-check-" + conversation.id())
                    .start(() -> runCheck(user, conversation.id(), handle, run));
        } catch (RuntimeException | Error ex) {
            log.warn("살펴보기를 시작하지 못했다 conversationId={}", conversation.id(), ex);
            if (check != null) {
                markFailed(check, ErrorCode.INTERNAL_ERROR.name());
            }
            turns.close(handle);
            throw ex;
        }
        return conversation.publicId();
    }

    /** 보고를 연 사용자만 시각을 적는다. 이미 연 보고를 다시 열어도 첫 시각을 보존한다. */
    @Transactional
    public void openReport(CurrentUser user, Long checkId) {
        ProactiveCheck check = checks.findByIdAndUserIdAndReportIsNotNull(checkId, user.id())
                .orElseThrow(
                        () -> new ApiException(ErrorCode.PROACTIVE_CHECK_NOT_FOUND, "no such proactive check report"));
        check.openReport(clock.instant());
    }

    private boolean hasUnreadReport(Long conversationId) {
        return checks.existsByConversationIdAndReportIsNotNullAndReportOpenedAtIsNull(conversationId);
    }

    /** 점검 줄 저장과 예약 실행 줄 연결은 Hermes 호출보다 먼저 같은 짧은 트랜잭션에서 끝낸다. */
    private ProactiveCheck saveLinked(ProactiveCheck check, Consumer<ProactiveCheck> beforeRun) {
        return transactions.execute(status -> {
            ProactiveCheck saved = checks.saveAndFlush(check);
            beforeRun.accept(saved);
            return saved;
        });
    }

    /** 같은 루트 session 으로 보낸 살펴보기가 상한에 닿았으면 참이다. 루트 session 이 비었으면 바꾸지 않는다. */
    private boolean renewsSession(Conversation conversation) {
        String rootSession = conversation.hermesRootSessionId();
        return rootSession != null
                && checks.countByConversationIdAndHermesRootSessionId(conversation.id(), rootSession)
                        >= properties.sessionMaxChecks();
    }

    /**
     * turn 을 돌리고, 어떻게 끝나든 잠금을 풀기 전에 끝을 정리한다. 정리는 {@link #finish} 가 한다. 정리가 실패해도 잠금은 푼다.
     */
    private void runCheck(CurrentUser owner, Long conversationId, TurnHandle handle, ProactiveCheckRun run) {
        boolean returned = false;
        RuntimeException failure = null;
        try {
            chat.runProactiveCheck(owner, conversationId, handle, run, event -> hub.publish(conversationId, event));
            returned = true;
        } catch (RuntimeException ex) {
            failure = ex;
        } finally {
            try {
                finish(conversationId, handle, run, returned, failure);
            } finally {
                turns.close(handle);
            }
        }
    }

    /**
     * 끝난 살펴보기를 문서의 「끝날 때」 순서로 정리한다. 잠금을 풀기 전에 부른다. 잠금을 풀면 닫기 리스너가 곧바로 다음 turn 을 정하므로,
     * 그 전에 위임 결과를 전했다고 적어 두어야 점검 대화에 자동 turn 이 열리지 않는다.
     *
     * <p>시간 상한 스레드를 끝내고, 돌아온 turn 이면 발견을 저장하고, 위임 수와 함께 줄을 적고, 그 트리의 위임 결과를 전했다고 적고,
     * 끝났다는 사건을 낸다. 각 단계가 실패해도 다음 단계로 넘어간다.
     *
     * <p>예외로 끝났어도 상한에 닿았거나 사용자의 중지가 확정됐으면 멈춘 것으로 적고 실패 알림 줄을 남기지 않는다. 실행 줄이 이미
     * {@code FAILED} 로 적혀 멈춤 알림 줄이 저장되지 않았으면 여기서 남긴다. 알림 줄은 한 살펴보기에 하나다.
     *
     * @param returned turn 이 예외 없이 돌아왔다
     * @param failure turn 이 던진 예외. 돌아왔거나 {@link Error} 로 끝났으면 null 이다
     */
    private void finish(
            Long conversationId, TurnHandle handle, ProactiveCheckRun run, boolean returned, RuntimeException failure) {
        recordQuietly(run::close, conversationId);
        Long rootId = run.rootExecutionId();
        int delegations = rootId == null ? 0 : countDelegations(rootId, conversationId);
        if (returned) {
            // 돌아왔으면 답 메시지는 저장됐다. 답을 저장하지 못한 turn 은 예외로 끝나 발견을 남기지 않는다.
            recordQuietly(run::saveFindings, conversationId);
            recordQuietly(() -> run.record(delegations), conversationId);
        } else if (run.limitStopped() || turns.isStopConfirmed(handle)) {
            log.info("멈춘 살펴보기가 예외로 끝났다 conversationId={}", conversationId, failure);
            if (!run.stoppedNoticeSaved()) {
                recordQuietly(() -> notices.post(conversationId, run.stoppedNotice()), conversationId);
            }
            recordQuietly(() -> run.record(delegations), conversationId);
            recordQuietly(
                    () -> notices.publicIdOf(conversationId)
                            .ifPresent(
                                    publicId -> hub.publish(conversationId, ChatEvent.stopped(publicId, null, rootId))),
                    conversationId);
        } else {
            String code = failure instanceof ApiException api ? api.code().name() : ErrorCode.INTERNAL_ERROR.name();
            log.warn("살펴보기가 실패했다 conversationId={} code={}", conversationId, code, failure);
            recordQuietly(() -> run.recordFailure(code, delegations), conversationId);
            recordQuietly(() -> notices.post(conversationId, FAILED_NOTICE), conversationId);
            String message = failure instanceof ApiException ? failure.getMessage() : "internal error";
            recordQuietly(() -> hub.publish(conversationId, ChatEvent.error(code, message)), conversationId);
        }
        if (rootId != null) {
            recordQuietly(() -> deliveryWriter.markTreeDelivered(rootId, clock.instant()), conversationId);
            recordQuietly(() -> events.publishEvent(new ProactiveCheckEnded(rootId)), conversationId);
        }
    }

    /** 그 트리에서 맡긴 위임 자식 수. 세지 못하면 0 으로 적는다. */
    private int countDelegations(Long rootId, Long conversationId) {
        try {
            return Math.toIntExact(executions.countByRootExecutionIdAndDelegationKeyIsNotNull(rootId));
        } catch (RuntimeException ex) {
            log.warn("살펴보기 트리의 위임 수를 세지 못했다 conversationId={} rootExecutionId={}", conversationId, rootId, ex);
            return 0;
        }
    }

    private void markFailed(ProactiveCheck check, String errorCode) {
        try {
            check.fail(errorCode, 0, 0, clock.instant());
            checks.save(check);
        } catch (RuntimeException ex) {
            log.warn("시작하지 못한 살펴보기를 실패로 적지 못했다 checkId={}", check.id(), ex);
        }
    }

    /** 끝난 뒤의 기록이 실패해도 잠금 풀기와 다음 기록으로 넘어간다. */
    private static void recordQuietly(Runnable work, Long conversationId) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            log.warn("살펴보기의 끝을 기록하지 못했다 conversationId={}", conversationId, ex);
        }
    }

    private ProactiveCheckRun.Deps deps() {
        return new ProactiveCheckRun.Deps(
                properties,
                checks,
                findings,
                messages,
                executions,
                contextAssembler,
                parser,
                renderer,
                reportFactory,
                chat,
                clock);
    }
}
