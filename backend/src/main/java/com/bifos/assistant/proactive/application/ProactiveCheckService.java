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
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 먼저 살펴보기의 진입점이다(ADR-077). 진입 경로는 {@code docs/backend/proactive-check.md} 의 「진입점」 이 갖는다.
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
    private final Clock clock;

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
        Agent agent = agents.requireStartable(user, agentCode);
        if (!readiness.check(agent).available()) {
            throw new ApiException(ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, "this agent cannot run a proactive check now");
        }
        OpenedCheck opened = checkConversations.findOrCreate(user, agent);
        Conversation conversation = opened.conversation();
        TurnHandle handle;
        try {
            handle = turns.open(user.id(), conversation.id());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.USER_BUSY && opened.created()) {
                checkConversations.deleteCreated(conversation.id());
            }
            throw ex;
        }
        ProactiveCheck check = null;
        try {
            check = checks.save(
                    ProactiveCheck.started(user.id(), agent.id(), conversation.id(), trigger, clock.instant()));
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

    /** 같은 루트 session 으로 보낸 살펴보기가 상한에 닿았으면 참이다. 루트 session 이 비었으면 바꾸지 않는다. */
    private boolean renewsSession(Conversation conversation) {
        String rootSession = conversation.hermesRootSessionId();
        return rootSession != null
                && checks.countByConversationIdAndHermesRootSessionId(conversation.id(), rootSession)
                        >= properties.sessionMaxChecks();
    }

    /**
     * turn 을 돌리고 결과를 살펴보기 줄에 적은 뒤 잠금을 푼다.
     *
     * <p>예외로 끝나면 줄을 {@code FAILED} 와 오류 코드로 적고, 실패 알림 줄을 남기고, 대화 SSE 로 {@code error} 를 보낸다. 줄을
     * 적다 실패해도 잠금은 푼다.
     */
    private void runCheck(CurrentUser owner, Long conversationId, TurnHandle handle, ProactiveCheckRun run) {
        try {
            chat.runProactiveCheck(owner, conversationId, handle, run, event -> hub.publish(conversationId, event));
            recordQuietly(run::record, conversationId);
        } catch (RuntimeException ex) {
            String code = ex instanceof ApiException api ? api.code().name() : ErrorCode.INTERNAL_ERROR.name();
            log.warn("살펴보기가 실패했다 conversationId={} code={}", conversationId, code, ex);
            recordQuietly(() -> run.recordFailure(code), conversationId);
            recordQuietly(() -> notices.post(conversationId, FAILED_NOTICE), conversationId);
            hub.publish(
                    conversationId,
                    ChatEvent.error(code, ex instanceof ApiException ? ex.getMessage() : "internal error"));
        } finally {
            turns.close(handle);
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
                properties, checks, findings, messages, executions, contextAssembler, parser, renderer, clock);
    }
}
