package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.AutoTurnResultSource;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.context.ContextBodyMode;
import com.bifos.assistant.context.ContextFreshness;
import com.bifos.assistant.context.ContextItem;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.context.ContextSource;
import com.bifos.assistant.context.ContextTrust;
import com.bifos.assistant.context.ResultHeader;
import com.bifos.assistant.hermes.dto.ConnectorErrorDetail;
import com.bifos.assistant.hermes.dto.ConnectorRecovery;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.shared.util.ExternalData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인해 실행한 호출의 결과를 그 요청이 나온 대화의 자동 turn 에 싣는다(ADR-050).
 *
 * <p>결과를 받았거나 결과를 모르는 줄만 낸다. 거절과 만료는 {@link ConnectorActionListener} 가 알림 줄만 남긴다.
 * 결과 본문은 모델 입력에만 넣고 알림 줄에는 이름과 상태만 쓴다.
 *
 * <p>결과마다 {@code CONNECTOR_RESULT} 문맥 항목을 함께 낸다(ADR-071). 신선도는 결과를 읽는 시각으로 판정한다. 처음 전할 때와
 * 다시 전할 때가 같은 함수로 머리줄과 항목을 만들어, 몇 시간 뒤에 다시 전한 결과에 「오래됨」 이 붙는다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorActionResultSource implements AutoTurnResultSource {

    static final String UNKNOWN_INPUT = "실행 여부를 알 수 없다. 다시 실행하지 말고 사용자에게 확인을 부탁한다.";

    /** 전달 묶음의 항목에 적는 출처 이름이다. 결과 이름은 승인 줄의 {@code public_id} UUID 글이다. */
    static final String SOURCE = "CONNECTOR_ACTION";

    private final ConnectorActionService actions;
    private final Clock clock;
    private final ContextProperties contextProperties;

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<AutoTurnResult> undelivered(Long conversationId) {
        Instant now = clock.instant();
        return actions.undeliveredResults(conversationId).stream()
                .map(result -> autoTurnResult(result, now))
                .toList();
    }

    @Override
    public void markDelivered(List<String> keys, Instant now) {
        actions.markDelivered(keys.stream().map(UUID::fromString).toList(), now);
    }

    @Override
    public List<Long> conversationsWithUndelivered() {
        return actions.conversationsWithUndelivered();
    }

    /** UUID 로 읽지 못하는 열쇠는 뺀다. 알림 줄과 입력은 처음 전할 때와 같은 글이다. */
    @Override
    public List<AutoTurnResult> resultsFor(Long conversationId, Long userId, List<String> keys) {
        List<UUID> actionIds = keys.stream()
                .map(ConnectorActionResultSource::actionIdOf)
                .flatMap(Optional::stream)
                .toList();
        Instant now = clock.instant();
        return actions.resultsFor(conversationId, userId, actionIds).stream()
                .map(result -> autoTurnResult(result, now))
                .toList();
    }

    private AutoTurnResult autoTurnResult(ConnectorActionResult result, Instant now) {
        Duration staleAfter = contextProperties.resultStaleAfter();
        ContextItem item = item(result, now, staleAfter);
        return new AutoTurnResult(
                result.actionId().toString(), notice(result), input(result, item.freshness(), staleAfter), item);
    }

    /** 승인 줄 하나의 문맥 항목이다. 본문은 입력에만 싣고 항목에 두지 않는다. 결과를 모르면 본문을 싣지 않은 항목이다. */
    private static ContextItem item(ConnectorActionResult result, Instant now, Duration staleAfter) {
        return new ContextItem(
                ContextSource.CONNECTOR_RESULT,
                "connector_action:" + result.actionId(),
                MemoryScope.USER,
                result.userId(),
                MemorySensitivity.SENSITIVE,
                ContextTrust.EXTERNAL,
                result.executedAt(),
                ResultHeader.freshnessOf(result.executedAt(), now, staleAfter),
                result.status() == ActionStatus.UNKNOWN ? ContextBodyMode.OMITTED : ContextBodyMode.INLINE,
                List.of(),
                null,
                null);
    }

    /** 복구 어휘를 모델에게 줄 안내 한 줄로 바꾼다. 커넥터는 어휘만 고르고 글은 Control Plane 이 정한다(ADR-092). */
    static String recoveryInput(ConnectorRecovery recovery) {
        return switch (recovery) {
            case RECHECK ->
                "복구: 승인한 뒤 대상이 바뀌어 실행하지 않았다. 같은 조건으로 다시 조회해 지금 상태를 사용자에게 보이고,"
                        + " 그대로 진행할지 다시 묻는다. 같은 인자로 다시 승인을 요청하지 않는다.";
            case RECONNECT -> "복구: 연결의 권한이나 값이 모자라다. 사용자에게 연결을 다시 등록하도록 안내한다." + " 커넥터 스킬에 절차가 있으면 그것을 따른다.";
            case FIX_INPUT -> "복구: 인자가 맞지 않았다. 인자를 고쳐 새로 승인을 요청한다.";
            case RETRY_LATER -> "복구: 서비스가 응답하지 않았다. 읽기 도구로 지금 상태를 확인한 뒤 다시 시도할지 사용자에게 묻는다.";
        };
    }

    private static Optional<UUID> actionIdOf(String key) {
        try {
            return Optional.of(UUID.fromString(key));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String notice(ConnectorActionResult result) {
        return switch (result.status()) {
            case SUCCEEDED -> "승인한 「" + result.title() + "」 실행이 끝났어요";
            case FAILED -> "승인한 「" + result.title() + "」 실행이 실패했어요";
            default -> "승인한 「" + result.title() + "」 실행 결과를 알 수 없어요. 그 서비스에서 확인해 주세요";
        };
    }

    /**
     * 첫 줄 뒤에 출처 머리줄을 두고 그 아래에 결과 본문을 잇는다. 머리줄에는 사람이 읽는 이름, 상태, 실행한 시각을 적는다.
     * 오래된 결과는 신선도와 안내 한 줄을 더한다(ADR-071).
     *
     * <p>도구의 원래 이름과 요청 번호는 싣지 않는다. 모델이 답에 옮겨 화면에 내부 값이 나오지 않게 하려는 것이다.
     *
     * <p>본문은 외부 서비스의 글이므로 {@code <external-data>} 로 감싸 지시가 아니라고 알린다.
     */
    private static String input(ConnectorActionResult result, ContextFreshness freshness, Duration staleAfter) {
        List<String> fields = new ArrayList<>(
                List.of("동작: " + result.title(), "상태: " + result.status().name()));
        Optional<ConnectorErrorDetail> detail = result.status() == ActionStatus.FAILED
                ? ConnectorErrorDetail.fromStored(result.resultText())
                : Optional.empty();
        if (result.status() == ActionStatus.FAILED && result.errorCode() != null) {
            fields.add("오류: " + result.errorCode());
        }
        detail.ifPresent(found -> {
            fields.add("오류 코드: " + found.code());
            if (!found.details().isEmpty()) {
                fields.add("세부: "
                        + found.details().entrySet().stream()
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .collect(Collectors.joining(", ")));
            }
        });
        StringBuilder input = new StringBuilder("승인한 동작의 결과가 도착했다.\n")
                .append(ResultHeader.render("승인한 동작", fields, result.executedAt(), freshness, staleAfter));
        if (result.status() == ActionStatus.UNKNOWN) {
            return input.append('\n').append(UNKNOWN_INPUT).toString();
        }
        if (result.status() == ActionStatus.FAILED) {
            // 실패 줄의 글은 Control Plane 이 검증해 저장한 오류 계약뿐이다. 본문으로 옮기지 않고 복구 안내만 잇는다.
            detail.map(ConnectorErrorDetail::recovery)
                    .ifPresent(recovery -> input.append('\n').append(recoveryInput(recovery)));
            return input.toString();
        }
        if (result.resultText() != null && !result.resultText().isBlank()) {
            input.append('\n').append(ExternalData.wrap(result.resultText()));
        }
        return input.toString();
    }
}
