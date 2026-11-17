package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.delivery.application.dto.AutoTurnResult;
import com.bifos.assistant.chat.delivery.application.port.AutoTurnResultSource;
import com.bifos.assistant.context.application.ResultHeader;
import com.bifos.assistant.context.config.ContextProperties;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.domain.type.NativeResultStatus;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.orchestration.infra.SubagentResultTexts;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hermes 내장 위임(delegate_task) 하위 에이전트의 결과를 그 대화의 자동 turn 에 싣는다(ADR-20261117 / native-delegation-wake).
 *
 * <p>결과는 끝 소식으로 등록 줄에 적힌 것이다. 아래를 모두 맞는 줄만 낸다.
 *
 * <ul>
 * <li>깨울 자식이고 끝 소식이 왔고 아직 전하지 않았다
 * <li>끝 소식이 {@link #SETTLE} 보다 오래됐다. Hermes 가 한 완료 단위의 hook 을 잇달아 부르므로 그 사이 다른 계기가 깨워도 한 단위가
 * 두 답으로 나뉘지 않는다
 * <li>origin 실행이 중지되지 않았다. 중지한 turn 의 자식은 {@code NativeDelegationSweep} 이 알림 줄로 끝낸다
 * </ul>
 *
 * <p>끝 소식 뒤에 사용자 turn 이 돌았어도 뺀다고 보지 않는다. 이름을 붙인 Hermes profile 은 부모 session 에 결과 줄을 남기지 않아
 * 그 turn 이 결과를 읽을 길이 없다(hermes/docs/hermes-contract.md 「API 위임 결과는 delivery 기록으로 남는다」). 결과 줄이 남는
 * profile 에서는 같은 결과를 한 번 더 정리할 수 있다. 결과를 놓치는 것보다 낫다.
 *
 * <p>입력에는 Control Plane 이 저장한 결과 글을 넣는다. 결과 글은 하위 에이전트가 쓴 글이라 {@code <external-data>} 로 감싼다.
 */
@Component
@RequiredArgsConstructor
public class NativeDelegationResultSource implements AutoTurnResultSource {

    /** 전달 묶음의 항목에 적는 출처 이름이다. 결과 이름은 등록 줄 번호다. */
    static final String SOURCE = "NATIVE_DELEGATION";

    /** 끝 소식 뒤 결과를 내기 전에 기다리는 시간이다. 한 완료 단위의 hook 이 모두 닿을 시간이다. */
    static final Duration SETTLE = Duration.ofSeconds(5);

    /** 이보다 오래전에 끝난 결과는 내지 않는다. 서버가 오래 내려갔다 떠도 옛 결과로 자동 turn 을 한꺼번에 열지 않는다. */
    static final Duration HORIZON = Duration.ofHours(24);

    static final String SUCCEEDED_NOTICE = "하위 작업이 끝났어요";
    static final String FAILED_NOTICE = "하위 작업을 하지 못했어요";

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final SubagentResultTexts texts;
    private final ContextProperties contextProperties;
    private final Clock clock;


    @Override
    public String source() {
        return SOURCE;
    }


    @Override
    public List<AutoTurnResult> undelivered(Long conversationId) {
        Instant now = clock.instant();
        return results(bindings.findSettledUndelivered(conversationId, now.minus(HORIZON), now.minus(SETTLE)), now);
    }


    /** 알림 줄을 저장하는 트랜잭션에 함께 든다. 따로 부르면 자기 트랜잭션에서 돈다. */
    @Override
    @Transactional
    public int markDelivered(List<String> keys, Instant now) {
        List<Long> ids = idsOf(keys);
        return ids.isEmpty() ? 0 : bindings.markDelivered(ids, now);
    }


    @Override
    public List<AutoTurnResult> resultsFor(Long conversationId, Long userId, List<String> keys) {
        List<Long> ids = idsOf(keys);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, HermesSessionBinding> found = bindings.findStoppedIn(conversationId, userId, ids).stream()
            .collect(Collectors.toMap(HermesSessionBinding::id, Function.identity()));
        List<HermesSessionBinding> ordered = ids.stream().map(found::get).filter(binding -> binding != null).toList();
        return results(ordered, clock.instant());
    }


    /** 기동 훑기용이다. {@link #undelivered} 와 같은 조건이다. */
    @Override
    public List<Long> conversationsWithUndelivered() {
        Instant now = clock.instant();
        return bindings.findConversationsWithSettledUndelivered(now.minus(HORIZON), now.minus(SETTLE));
    }


    @Override
    @Transactional
    public void noticesSaved(Map<String, Long> messageIdByKey) {
        messageIdByKey.forEach((key, messageId) -> idOf(key).ifPresent(id -> bindings.markNoticed(id, messageId)));
    }


    private List<AutoTurnResult> results(List<HermesSessionBinding> rows, Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> origins = rows.stream().map(HermesSessionBinding::originExecutionId).distinct().toList();
        List<Long> unattended = origins.stream().filter(origin -> executions.countUnattendedIn(List.of(origin)) > 0).toList();
        List<AutoTurnResult> results = new ArrayList<>();
        rows.stream()
            .sorted(Comparator.comparing(HermesSessionBinding::stoppedAt).thenComparing(HermesSessionBinding::id))
            .forEach(row -> results.add(result(row, unattended.contains(row.originExecutionId()), now)));
        return results;
    }


    private AutoTurnResult result(HermesSessionBinding row, boolean unattended, Instant now) {
        Optional<String> goal = texts.open(
            row.id(), row.userId(), SubagentResultTexts.GOAL_PREVIEW, row.resultKeyId(), row.goalPreview())
            .filter(text -> !text.isBlank());
        Optional<String> body = texts.open(
            row.id(), row.userId(), SubagentResultTexts.RESULT_TEXT, row.resultKeyId(), row.resultText())
            .filter(text -> !text.isBlank());
        NativeResultStatus status = row.resultStatus() == null ? NativeResultStatus.FAILED : row.resultStatus();
        return new AutoTurnResult(
            String.valueOf(row.id()),
            notice(status, goal),
            input(row, status, goal, body, now),
            null,
            unattended,
            status.name());
    }


    static String notice(NativeResultStatus status, Optional<String> goal) {
        String head = status == NativeResultStatus.SUCCEEDED ? SUCCEEDED_NOTICE : FAILED_NOTICE;
        return goal.map(text -> head + " · " + text).orElse(head);
    }


    private String input(
        HermesSessionBinding row, NativeResultStatus status, Optional<String> goal, Optional<String> body, Instant now) {
        Duration staleAfter = contextProperties.resultStaleAfter();
        List<String> fields = new ArrayList<>();
        goal.ifPresent(text -> fields.add("맡긴 일: " + text));
        fields.add("상태: " + status.name());
        StringBuilder input = new StringBuilder("Hermes 하위 작업의 결과가 도착했다.\n")
            .append(ResultHeader.render(
                "Hermes 하위 작업",
                fields,
                row.stoppedAt(),
                ResultHeader.freshnessOf(row.stoppedAt(), now, staleAfter),
                staleAfter))
            .append("\n이 결과는 Hermes 대화 이력에 없을 수 있다. 이미 이 결과로 답했으면 다시 길게 정리하지 않는다.");
        body.ifPresent(text -> input.append('\n').append(ExternalData.wrap(text)));
        return input.toString();
    }


    private static List<Long> idsOf(List<String> keys) {
        return keys.stream().map(NativeDelegationResultSource::idOf).flatMap(Optional::stream).distinct().toList();
    }


    private static Optional<Long> idOf(String key) {
        try {
            return Optional.of(Long.valueOf(key));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
