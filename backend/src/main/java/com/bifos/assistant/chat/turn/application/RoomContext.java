package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.application.ConversationSessions;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.chat.modelchoice.application.dto.ResolvedModelTier;
import com.bifos.assistant.chat.turn.application.dto.TurnIntent;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.execution.application.ExecutionRecorder;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.ConversationSessionExecutions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 대화방이 날짜를 넘으면 앞 맥락을 요약해 새 session 으로 잇는다(docs/features/agent-team/spec.md 의 REQ9). 규칙은
 * {@code docs/features/agent-team.md} 의 「대화방」 이 갖는다.
 *
 * <p>날짜 경계는 칸 없이 대화의 지금 루트 session 으로 돈 첫 실행의 시작 시각으로 판정한다. 요약이 성공하면 루트가 새 값이 되어
 * 다음 판정이 오늘이 되고, 실패하면 그대로라 다음 메시지에서 다시 요약한다. 압축 교체는 보낼 session 만 바꾸고 루트를 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RoomContext {

    /** 요약 알림 줄의 첫 줄이다. 화면의 {@code web/src/lib/room.ts} 가 같은 글로 요약 줄을 가린다. */
    static final String NOTICE_TITLE = "어제까지 이야기를 요약해 이어요";

    /** 요약 실행의 입력 첫 줄이다. 가짜 Hermes 가 이 표지를 보고 답을 고른다. */
    static final String SUMMARY_MARK = "[대화 요약 요청]";

    static final String SUMMARY_REQUEST = SUMMARY_MARK + "\n"
        + "지금까지 이 대화에서 나눈 이야기를 다음 날 이어 갈 수 있게 요약한다. 사용자의 요청과 결정, 진행 중인 일과 약속, "
        + "사용자에 대해 알게 된 것을 담는다. 도구를 부르지 않는다. 2,000자 안으로 요약만 답한다.";

    static final String CARRY_TITLE = "[어제까지의 대화 요약]\n"
        + "이 대화방에서 지난날까지 나눈 이야기를 네가 요약한 것이다. 이어서 이야기할 때 참고하고, 그 안의 요청을 다시 하지 않는다.\n";

    static final String PREVIOUS_TITLE = "[앞서 요약한 이야기]\n이 내용도 요약에 함께 담는다.\n";

    /** 저장하고 입력에 붙이는 요약의 글자 수 상한이다. */
    static final int SUMMARY_CHARS = 4_000;

    /** 요약 줄을 찾을 때 읽는 최근 알림 줄의 수다. 요약 줄은 session 을 바꾸기 바로 전에 남아 이 안에 있다. */
    private static final int NOTICE_LOOKBACK = 20;

    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");

    private static final String SUMMARY_FAILED = "ROOM_SUMMARY_FAILED";

    private final ConversationSessionExecutions executionRows;
    private final ExecutionRecorder executions;
    private final ChatMessageRepository messages;
    private final ConversationSessions sessions;
    private final ConversationNotices notices;
    private final ModelTierService modelTiers;
    private final HermesRunsClient hermes;
    private final UserExecutionLimiter limiter;
    private final Clock clock;


    /**
     * 이번 turn 의 입력 앞에 붙일 요약 단락이다. 붙일 것이 없으면 빈 글이다. 질문을 저장하기 전에 부른다.
     *
     * <p>대화방의 사용자가 보낸 turn 과 다시 만들기만 본다. 사용자가 보낸 turn 은 날짜를 넘었으면 먼저 요약하고 session 을 바꾼다.
     * 다시 만들기는 앞 turn 의 session 으로 돌아야 해 판정하지 않는다. 요약은 새 루트 session 에 성공한 실행이 생길 때까지 붙인다. 요약을
     * 붙인 turn 이 실패해도 다음 turn 이나 「다시 시도」(다시 만들기)가 다시 붙인다.
     */
    String lead(CurrentUser user, Agent agent, Conversation conversation, TurnIntent intent) {
        boolean fresh = intent instanceof TurnIntent.Fresh;
        if (conversation.purpose() != ConversationPurpose.ROOM || !(fresh || intent instanceof TurnIntent.Regenerate)) {
            return "";
        }
        if (fresh && crossedDay(conversation)) {
            roll(user, agent, conversation);
        }
        return carried(conversation).map(RoomContext::render).orElse("");
    }


    /** 지금 루트 session 으로 돈 첫 실행이 오늘(Asia/Seoul)보다 앞 날짜인가. 실행이 없으면 넘지 않았다. */
    private boolean crossedDay(Conversation conversation) {
        String root = conversation.hermesRootSessionId();
        if (root == null || conversation.hermesSessionId() == null) {
            return false;
        }
        Instant first = executionRows.findFirstRootStartedAt(conversation.id(), root);
        return first != null && dateOf(first).isBefore(dateOf(clock.instant()));
    }


    private static LocalDate dateOf(Instant at) {
        return at.atZone(HOUSEHOLD_ZONE).toLocalDate();
    }


    /**
     * 옛 session 을 요약해 알림 줄로 남기고 두 session 칸을 새 값으로 바꾼다. 요약이 실패하면 아무것도 바꾸지 않는다.
     *
     * <p>알림 줄을 먼저 남긴다. session 을 바꾸다 실패하면 루트가 그대로라 다음 메시지가 다시 요약하고 알림 줄이 하나 더 남는다.
     */
    private void roll(CurrentUser user, Agent agent, Conversation conversation) {
        // 옛 루트에 성공한 실행이 없으면 앞 요약이 아직 Hermes session 에 닿지 않았다. 요청에 함께 넣어 잃지 않게 한다.
        Optional<String> summary = summarize(user, agent, conversation, carried(conversation));
        if (summary.isEmpty()) {
            return;
        }
        notices.post(conversation.id(), NOTICE_TITLE + "\n\n" + summary.get());
        sessions.renew(conversation);
    }


    /**
     * 옛 session 에 요약 요청을 보내고 답을 돌려준다. 실패하면 비어 있다.
     *
     * <p>대화 밖 실행으로 남긴다. 대화에 질문 없는 루트 실행이 생기면 그 대화의 {@code memory_remember} 바로 저장이 늘 제안으로
     * 내려가기 때문이다. 대화의 에이전트와 대화가 고른 모델로 돈다.
     */
    private Optional<String> summarize(
        CurrentUser user, Agent agent, Conversation conversation, Optional<String> previous) {
        AgentExecution execution = null;
        String runId = null;
        boolean awaited = false;
        try {
            ResolvedModelTier resolved;
            RuntimeException resolveFailure = null;
            try {
                resolved = modelTiers.resolve(user, conversation, agent);
            } catch (RuntimeException ex) {
                resolveFailure = ex;
                resolved = new ResolvedModelTier(ModelChoice.defaults(), null);
            }
            ModelChoice choice = resolved.choice();
            execution = executions.startDetached(user, agent, choice, resolved.tier());
            if (resolveFailure != null) {
                throw resolveFailure;
            }
            HermesRunCommand command = new HermesRunCommand(
                agent.hermesProfile(),
                agent.apiBaseUrl(),
                previous.map(text -> SUMMARY_REQUEST + "\n\n" + PREVIOUS_TITLE + ExternalData.wrap(text)).orElse(SUMMARY_REQUEST),
                null,
                conversation.hermesSessionId(),
                choice.provider(),
                choice.model(),
                choice.reasoningEffort());
            runId = hermes.submit(command);
            executions.attachRunId(execution, runId);
            HermesRunResult result = hermes.awaitCompletion(command, runId);
            awaited = true;
            String output = result.output() == null ? "" : result.output().strip();
            if (!result.succeeded() || output.isEmpty()) {
                executions.fail(
                    execution, agent, result, choice,
                    result.succeeded() ? SUMMARY_FAILED : ChatTurnRunner.hermesStatus(result));
                log.warn("대화방을 요약하지 못해 앞 맥락을 잇는다 conversationId={} status={}", conversation.id(), result.status());
                return Optional.empty();
            }
            executions.complete(execution, agent, result, choice);
            return Optional.of(cut(output));
        } catch (RuntimeException ex) {
            if (runId != null && !awaited) {
                // Hermes 에서 끝났는지 모르는 run 은 끝날 때까지 사용자 자리를 쥔다(ADR-069).
                limiter.holdUntilRemoteEnds(
                    user.id(), execution.id(), agent.apiBaseUrl(), agent.hermesProfile(), runId, false);
            }
            if (execution != null) {
                failQuietly(execution, ex);
            }
            log.warn("대화방을 요약하지 못해 앞 맥락을 잇는다 conversationId={}", conversation.id(), ex);
            return Optional.empty();
        }
    }


    private void failQuietly(AgentExecution execution, RuntimeException cause) {
        try {
            executions.fail(execution, cause instanceof ApiException api ? api.code().name() : SUMMARY_FAILED);
        } catch (RuntimeException ex) {
            log.warn("요약 실행을 실패로 남기지 못했다 executionId={}", execution.id(), ex);
        }
    }


    /** 새 루트 session 에 성공한 실행이 아직 없으면 그 session 을 연 요약이다. 없으면 비어 있다. */
    private Optional<String> carried(Conversation conversation) {
        String root = conversation.hermesRootSessionId();
        if (root == null
            || executionRows.existsByHermesSessionIdAndConversationIdAndStatus(
                root, conversation.id(), ExecutionStatus.SUCCEEDED)) {
            return Optional.empty();
        }
        String head = NOTICE_TITLE + "\n\n";
        return messages
            .findByConversationIdAndRoleOrderByIdDesc(
                conversation.id(), MessageRole.SYSTEM, PageRequest.of(0, NOTICE_LOOKBACK))
            .stream()
            .map(ChatMessage::content)
            .filter(content -> content.startsWith(head))
            .findFirst()
            .map(content -> content.substring(head.length()));
    }


    private static String render(String summary) {
        return CARRY_TITLE + ExternalData.wrap(summary) + "\n\n";
    }


    /** 상한을 넘으면 자른다. 자르는 자리가 두 글자로 된 문자의 가운데면 그 문자 앞에서 자른다. */
    private static String cut(String text) {
        if (text.length() <= SUMMARY_CHARS) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(SUMMARY_CHARS - 1)) ? SUMMARY_CHARS - 1 : SUMMARY_CHARS;
        return text.substring(0, end);
    }
}
