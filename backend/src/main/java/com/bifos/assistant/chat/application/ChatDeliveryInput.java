package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.chat.application.model.DeliveryInput;
import com.bifos.assistant.chat.application.model.DeliveryItemRef;
import com.bifos.assistant.context.ContextBodyMode;
import com.bifos.assistant.context.ContextFreshness;
import com.bifos.assistant.context.ContextItem;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.context.ContextSource;
import com.bifos.assistant.context.ContextTrust;
import com.bifos.assistant.context.ResultHeader;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 저장된 결과로 Hermes 입력과 문맥 항목을 만든다. */
@Component
@Slf4j
@RequiredArgsConstructor
class ChatDeliveryInput {
    private final AgentService agents;
    private final AgentExecutionRepository executionRepository;
    private final Clock clock;
    private final List<AutoTurnResultSource> resultSources;
    private final ContextProperties contextProperties;

    /**
     * 묶음의 항목을 결과를 낸 쪽의 줄에서 다시 읽어 자동 turn 과 같은 모양의 입력과 결과 항목을 만든다.
     *
     * <p>신선도는 지금 시각으로 다시 판정한다. 몇 시간 뒤에 다시 전하면 머리줄에 「오래됨」 이 붙는다(ADR-071).
     *
     * <p>위임 결과는 그 사용자와 그 대화의 끝난 실행 줄만 항목 순서로 쓴다. 그 밖의 결과는 출처 이름이 같은 {@link
     * AutoTurnResultSource} 가 다시 읽는다. 맞는 구현이 없으면 경고 로그만 남기고 뺀다. 지워졌거나 남의 것인 결과는 빼고
     * 넘긴다.
     *
     * @throws ApiException {@code DELIVERY_NOT_RETRYABLE}. 남은 결과가 없을 때
     */
    DeliveryInput retryInput(CurrentUser user, Long conversationId, List<DeliveryItemRef> items) {
        List<Long> executionIds = new ArrayList<>();
        Map<String, List<String>> keysBySource = new LinkedHashMap<>();
        for (DeliveryItemRef item : items) {
            if (!ResultDeliveryRecorder.DELEGATION_SOURCE.equals(item.source())) {
                keysBySource
                        .computeIfAbsent(item.source(), source -> new ArrayList<>())
                        .add(item.resultKey());
                continue;
            }
            try {
                executionIds.add(Long.valueOf(item.resultKey()));
            } catch (NumberFormatException ex) {
                log.warn("실행 번호로 읽지 못하는 위임 결과 항목을 뺀다 resultKey={}", item.resultKey());
            }
        }
        Map<Long, AgentExecution> found = executionRepository.findAllById(executionIds).stream()
                .filter(execution -> user.id().equals(execution.userId())
                        && conversationId.equals(execution.conversationId())
                        && (execution.status() == ExecutionStatus.SUCCEEDED
                                || execution.status() == ExecutionStatus.FAILED))
                .collect(Collectors.toMap(AgentExecution::id, execution -> execution));
        List<AgentExecution> results = executionIds.stream()
                .distinct()
                .map(found::get)
                .filter(Objects::nonNull)
                .toList();
        List<AutoTurnResult> extras = new ArrayList<>();
        keysBySource.forEach((source, keys) -> resultSources.stream()
                .filter(candidate -> candidate.source().equals(source))
                .findFirst()
                .ifPresentOrElse(
                        candidate -> extras.addAll(candidate.resultsFor(conversationId, user.id(), keys)),
                        () -> log.warn("출처 이름에 맞는 결과 구현이 없어 그 항목을 뺀다 source={}", source)));
        if (results.isEmpty() && extras.isEmpty()) {
            throw ResultDeliveryRecorder.notRetryable();
        }
        return deliveryInput(
                results, resultAgentsOf(results), extras, clock.instant(), contextProperties.resultStaleAfter());
    }

    /**
     * 결과마다 출처 머리줄을 두고, 답이 있으면 그 아래에 잇는다. 머리줄에는 에이전트 이름, 실행 번호, 상태, 끝난 시각을 적고 실패는
     * 오류 코드를 더한다. 오래된 결과는 신선도와 안내 한 줄을 더한다(ADR-071).
     *
     * <p>연결용 에이전트의 답은 외부 서비스의 글을 담으므로 {@code <external-data>} 로 감싸 지시가 아니라고 알린다.
     * 에이전트 행이 없는 결과도 출처를 모르므로 감싼다. 감싸도 모델이 그 글을 따르지 않는다는 보장은 없다(ADR-049).
     *
     * <p>결과마다 {@code DELEGATION_RESULT} 항목을 하나씩 만든다. 본문은 입력에만 싣고 항목에 두지 않는다.
     */
    static DeliveryInput delegationInput(
            List<AgentExecution> results, Map<Long, Agent> resultAgents, Instant now, Duration staleAfter) {
        StringBuilder input = new StringBuilder("맡긴 일의 결과가 도착했다.");
        List<ContextItem> items = new ArrayList<>();
        for (AgentExecution result : results) {
            boolean external = isExternalResult(result, resultAgents);
            ContextItem item = delegationItem(result, external, now, staleAfter);
            items.add(item);
            List<String> fields = new ArrayList<>(List.of(
                    "에이전트: " + agentName(result, resultAgents),
                    "실행 번호: " + result.id(),
                    "상태: " + result.status().name()));
            if (result.status() == ExecutionStatus.FAILED) {
                fields.add("오류: " + result.errorCode());
            }
            input.append("\n\n").append(ResultHeader.render("맡긴 일", fields, item.asOf(), item.freshness(), staleAfter));
            if (result.outputText() != null && !result.outputText().isBlank()) {
                input.append('\n').append(external ? ExternalData.wrap(result.outputText()) : result.outputText());
            }
        }
        return new DeliveryInput(input.toString(), items);
    }

    /**
     * 위임 결과 하나의 문맥 항목이다. 그 사용자와 그 대화의 실행만 오므로 실행 줄의 사용자가 대화 주인이다.
     *
     * @param external 커넥터 에이전트의 답이거나 출처를 모르는 답이다
     */
    static ContextItem delegationItem(AgentExecution result, boolean external, Instant now, Duration staleAfter) {
        ContextFreshness freshness = ResultHeader.freshnessOf(result.finishedAt(), now, staleAfter);
        return new ContextItem(
                ContextSource.DELEGATION_RESULT,
                "execution:" + result.id(),
                MemoryScope.USER,
                result.userId(),
                MemorySensitivity.SENSITIVE,
                external ? ContextTrust.EXTERNAL : ContextTrust.AGENT,
                result.finishedAt(),
                freshness,
                ContextBodyMode.INLINE,
                List.of(),
                null,
                null);
    }

    /**
     * 자동 turn 과 다시 전달이 함께 쓰는 Hermes 입력과 결과 항목이다. 위임 결과의 단락이 먼저이고, 그 뒤로 그 밖의 결과의 단락을
     * 빈 줄로 잇는다. 항목도 같은 순서다. 항목이 없는 그 밖의 결과는 단락만 싣는다.
     *
     * @param now 묶음을 만든 시각. 결과의 신선도를 이 시각으로 판정한다
     */
    static DeliveryInput deliveryInput(
            List<AgentExecution> results,
            Map<Long, Agent> resultAgents,
            List<AutoTurnResult> extras,
            Instant now,
            Duration staleAfter) {
        StringBuilder input = new StringBuilder();
        List<ContextItem> items = new ArrayList<>();
        if (!results.isEmpty()) {
            DeliveryInput delegation = delegationInput(results, resultAgents, now, staleAfter);
            input.append(delegation.input());
            items.addAll(delegation.items());
        }
        for (AutoTurnResult extra : extras) {
            if (!input.isEmpty()) {
                input.append("\n\n");
            }
            input.append(extra.input());
            if (extra.item() != null) {
                items.add(extra.item());
            }
        }
        return new DeliveryInput(input.toString(), items);
    }

    /** 위임 결과를 낸 에이전트들이다. 결과가 없으면 읽지 않는다. */
    Map<Long, Agent> resultAgentsOf(List<AgentExecution> results) {
        return results.isEmpty()
                ? Map.of()
                : agents.byIds(results.stream().map(AgentExecution::agentId).toList());
    }

    static boolean isExternalResult(AgentExecution execution, Map<Long, Agent> resultAgents) {
        Agent agent = resultAgents.get(execution.agentId());
        return agent == null || agent.connectorManaged();
    }

    static String delegationNotice(List<AgentExecution> results, Map<Long, Agent> resultAgents) {
        String first = agentName(results.getFirst(), resultAgents);
        if (results.size() == 1) {
            return first + " 에이전트의 결과가 도착했어요";
        }
        return first + " 외 " + (results.size() - 1) + "개 에이전트의 결과가 도착했어요";
    }

    /** 에이전트 행이 없으면 실행 줄에 적힌 profile 이름으로 대신한다. */
    static String agentName(AgentExecution execution, Map<Long, Agent> resultAgents) {
        Agent agent = resultAgents.get(execution.agentId());
        return agent == null ? execution.profileName() : agent.name();
    }
}
