package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.turn.application.dto.TurnIntent;
import com.bifos.assistant.context.application.ResultHeader;
import com.bifos.assistant.context.config.ContextProperties;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 지난 {@code @} 맡김을 Hermes 입력에 넣는 단락을 만든다(ADR-20261112 / mention-delegation).
 *
 * <p>{@code @} 맡기기는 없앴지만(비서 중심 대화 스펙 REQ6) 그 전에 오간 맡김이 대화에 남아 있다. 대화 에이전트의 turn 은 지난 자기
 * turn 뒤에 오간 맡김을 입력 앞에 넣는다. 대화 에이전트의 Hermes session 에는 맡김이 없기 때문이다. 이번 turn 의 질문을 저장하기
 * 전에 부른다.
 */
@Component
@RequiredArgsConstructor
class MentionInputs {
    /** 맡김 질문 하나를 자르는 글자 수다. */
    static final int LINE_CHARS = 1_000;

    /** 대화 에이전트에게 싣는 맡김의 수다. 더 오래된 것은 뺀다. */
    static final int CARRIED_MENTIONS = 5;

    /** 대화 에이전트에게 싣는 맡김 답 하나를 자르는 글자 수다. */
    static final int ANSWER_CHARS = 2_000;

    static final String CARRY_TITLE = "[사용자가 @ 로 다른 에이전트에게 바로 맡긴 일]\n"
        + "너를 거치지 않고 오갔다. 사용자가 이어서 물으면 참고하고, 같은 일을 다시 하지 않는다.";

    static final String USER_MESSAGE_LABEL = "[사용자 메시지]\n";

    private static final String DELETED_AGENT = "지운 에이전트";

    private final ChatTurnRouting routing;
    private final MentionLines mentionLines;
    private final AgentService agents;
    private final ContextProperties contextProperties;
    private final Clock clock;


    /**
     * 이번 turn 의 입력 앞에 붙일 단락이다. 붙일 것이 없으면 빈 글이다.
     *
     * <p>사용자가 보낸 turn·자동 결과 전달 turn·예약 turn 이면 지난 자기 turn 뒤에 {@code @} 로 오간 맡김이다. {@code @} 맡기기는
     * 없앴지만 그 전에 남은 대화가 있어 읽는다. 다시 만들기와 먼저 살펴보기는 싣지 않는다.
     */
    String lead(Conversation conversation, TurnIntent intent) {
        if (!(intent instanceof TurnIntent.Fresh
            || intent instanceof TurnIntent.DelegationResults
            || intent instanceof TurnIntent.Scheduled)) {
            return "";
        }
        String carried = carryOver(conversation);
        if (carried.isEmpty()) {
            return "";
        }
        return intent instanceof TurnIntent.Fresh ? carried + USER_MESSAGE_LABEL : carried;
    }


    /**
     * 대화 에이전트의 지난 turn 뒤에 오간 맡김이다. 없으면 빈 글이다.
     *
     * <p>줄을 뒤에서부터 읽고 알림 줄은 건너뛴다. 맡김의 질문이나 답이면 모으고, 아니면 멈춘다. 대화의 에이전트가 쓴 답이나 그
     * 질문이 경계다. 맡김이 하나도 없는 대화는 줄을 읽지 않는다.
     */
    String carryOver(Conversation conversation) {
        List<AgentExecution> mentions = mentionLines.of(conversation);
        if (mentions.isEmpty()) {
            return "";
        }
        Map<Long, AgentExecution> byId = mentions.stream()
            .collect(Collectors.toMap(AgentExecution::id, Function.identity()));
        Map<Long, AgentExecution> byQuestion = mentions.stream()
            .filter(mention -> mention.questionMessageId() != null)
            .collect(Collectors.toMap(AgentExecution::questionMessageId, Function.identity(), (first, second) -> first));
        List<ChatMessage> active = routing.activeMessages(conversation.id());
        Map<Long, Exchange> collected = new LinkedHashMap<>();
        for (int index = active.size() - 1; index >= 0; index--) {
            ChatMessage message = active.get(index);
            if (message.role() == MessageRole.SYSTEM) {
                continue;
            }
            AgentExecution mention = message.role() == MessageRole.USER
                ? byQuestion.get(message.id())
                : message.executionId() == null ? null : byId.get(message.executionId());
            if (mention == null) {
                break;
            }
            Exchange exchange = collected.computeIfAbsent(mention.id(), id -> new Exchange(mention));
            if (message.role() == MessageRole.USER) {
                exchange.question = message.content();
            } else {
                exchange.answer = message.content();
            }
        }
        if (collected.isEmpty()) {
            return "";
        }
        List<Exchange> ordered = new ArrayList<>(collected.values()).reversed();
        return render(ordered.subList(Math.max(0, ordered.size() - CARRIED_MENTIONS), ordered.size()));
    }


    /** 맡김마다 출처 머리줄, 질문, 감싼 답의 단락이다. 오래된 것부터다. */
    private String render(List<Exchange> carried) {
        Map<Long, Agent> found = agents.byIds(carried.stream().map(exchange -> exchange.execution.agentId()).toList());
        Instant now = clock.instant();
        Duration staleAfter = contextProperties.resultStaleAfter();
        StringBuilder text = new StringBuilder(CARRY_TITLE);
        for (Exchange exchange : carried) {
            AgentExecution execution = exchange.execution;
            List<String> fields = new ArrayList<>(List.of(
                "에이전트: " + nameOf(AgentLabel.of(found.get(execution.agentId()))),
                "실행 번호: " + execution.id(),
                "상태: " + execution.status().name()));
            if (execution.status() == ExecutionStatus.FAILED && execution.errorCode() != null) {
                fields.add("오류: " + execution.errorCode());
            }
            text.append("\n\n")
                .append(ResultHeader.render(
                    "@ 로 맡긴 일",
                    fields,
                    execution.finishedAt(),
                    ResultHeader.freshnessOf(execution.finishedAt(), now, staleAfter),
                    staleAfter));
            if (exchange.question != null) {
                text.append("\n사용자: ").append(cut(exchange.question, LINE_CHARS));
            }
            if (exchange.answer != null && !exchange.answer.isBlank()) {
                text.append('\n').append(ExternalData.wrap(cut(exchange.answer, ANSWER_CHARS)));
            }
        }
        return text.append("\n\n").toString();
    }


    private static String nameOf(AgentLabel label) {
        return label.name() == null ? DELETED_AGENT : label.name();
    }


    static String cut(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }


    /** 맡김 하나의 질문과 답이다. 줄을 뒤에서부터 읽으며 채운다. */
    private static final class Exchange {
        private final AgentExecution execution;
        private String question;
        private String answer;


        private Exchange(AgentExecution execution) {
            this.execution = execution;
        }
    }
}
