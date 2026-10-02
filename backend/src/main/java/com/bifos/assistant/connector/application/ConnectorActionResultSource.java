package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.AutoTurnResultSource;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.shared.util.ExternalData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인해 실행한 호출의 결과를 그 요청이 나온 대화의 자동 turn 에 싣는다(ADR-050).
 *
 * <p>결과를 받았거나 결과를 모르는 줄만 낸다. 거절과 만료는 {@link ConnectorActionListener} 가 알림 줄만 남긴다.
 * 결과 본문은 모델 입력에만 넣고 알림 줄에는 이름과 상태만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorActionResultSource implements AutoTurnResultSource {

    static final String UNKNOWN_INPUT = "실행 여부를 알 수 없다. 다시 실행하지 말고 사용자에게 확인을 부탁한다.";

    private final ConnectorActionService actions;

    @Override
    public List<AutoTurnResult> undelivered(Long conversationId) {
        return actions.undeliveredResults(conversationId).stream()
                .map(result -> new AutoTurnResult(result.actionId().toString(), notice(result), input(result)))
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

    private static String notice(ConnectorActionResult result) {
        return switch (result.status()) {
            case SUCCEEDED -> "승인한 「" + result.title() + "」 실행이 끝났어요";
            case FAILED -> "승인한 「" + result.title() + "」 실행이 실패했어요";
            default -> "승인한 「" + result.title() + "」 실행 결과를 알 수 없어요. 그 서비스에서 확인해 주세요";
        };
    }

    /**
     * 머리줄에 사람이 읽는 이름과 상태를 두고 그 아래에 결과 본문을 잇는다.
     *
     * <p>도구의 원래 이름과 요청 번호는 싣지 않는다. 모델이 답에 옮겨 화면에 내부 값이 나오지 않게 하려는 것이다.
     *
     * <p>본문은 외부 서비스의 글이므로 {@code <external-data>} 로 감싸 지시가 아니라고 알린다.
     */
    private static String input(ConnectorActionResult result) {
        StringBuilder input = new StringBuilder("승인한 동작의 결과가 도착했다.\n[동작: ")
                .append(result.title())
                .append(", 상태: ")
                .append(result.status().name());
        if (result.status() == ActionStatus.FAILED && result.errorCode() != null) {
            input.append(", 오류: ").append(result.errorCode());
        }
        input.append(']');
        if (result.status() == ActionStatus.UNKNOWN) {
            return input.append('\n').append(UNKNOWN_INPUT).toString();
        }
        if (result.resultText() != null && !result.resultText().isBlank()) {
            input.append('\n').append(ExternalData.wrap(result.resultText()));
        }
        return input.toString();
    }
}
