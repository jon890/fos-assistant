package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
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
import com.bifos.assistant.usage.execution.infra.NativeResultReadChecks;
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
 * <li>이미 읽지 않았다. 대화 에이전트로 Hermes 에 제출된 루트 실행이 끝 소식 {@link #READ_MARGIN} 뒤에 시작했으면 그 turn 이 Hermes
 * 이력의 결과 줄을 읽었다고 본다. 끝 소식이 결과 줄보다 먼저 오므로 그 안에 시작한 turn 은 읽지 않은 것으로 본다
 * </ul>
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

    /** 끝 소식 뒤 Hermes 가 부모 session 에 결과 줄을 남길 시간이다. 이보다 늦게 시작한 turn 은 결과를 읽었다고 본다. */
    static final Duration READ_MARGIN = Duration.ofSeconds(30);

    /** 이보다 오래전에 끝난 결과는 내지 않는다. 그동안 전하지 못한 결과는 다음 사용자 메시지가 Hermes 이력으로 읽는다. */
    static final Duration HORIZON = Duration.ofHours(24);

    static final String SUCCEEDED_NOTICE = "하위 작업이 끝났어요";
    static final String FAILED_NOTICE = "하위 작업을 하지 못했어요";

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final NativeResultReadChecks readChecks;
    private final ConversationRepository conversations;
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
        List<HermesSessionBinding> settled = bindings.findSettledUndelivered(conversationId, now.minus(HORIZON), now.minus(SETTLE));
        if (settled.isEmpty()) {
            return List.of();
        }
        Optional<Long> agentId = conversations.findById(conversationId).map(Conversation::agentId);
        List<HermesSessionBinding> unread = settled.stream()
            .filter(binding -> agentId.isEmpty() || !readChecks.existsSubmittedRootStartedAfter(
                conversationId, agentId.get(), binding.stoppedAt().plus(READ_MARGIN)))
            .toList();
        return results(unread, now);
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


    /** 기동 훑기용이다. 이미 읽은 결과만 남은 대화는 빼야 깨운 뒤 아무것도 하지 않는 일이 줄어 대화마다 다시 확인한다. */
    @Override
    public List<Long> conversationsWithUndelivered() {
        Instant now = clock.instant();
        return bindings.findConversationsWithSettledUndelivered(now.minus(HORIZON), now.minus(SETTLE)).stream()
            .filter(conversationId -> !undelivered(conversationId).isEmpty())
            .toList();
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
            .append("\n같은 결과가 Hermes 대화 이력에도 있다. 이미 정리했으면 다시 길게 정리하지 않는다.");
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
