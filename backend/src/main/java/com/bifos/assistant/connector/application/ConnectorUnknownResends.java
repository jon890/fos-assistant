package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.hermes.dto.ConnectorUnknownDetail;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 호출이 결과를 모르는 쓰기를 같은 재시도 식별자로 다시 보내는 것인지 본다(ADR-20261013 unknown-retry-key).
 *
 * <p>그 사용자가 그 커넥터의 그 도구로 남긴 {@code UNKNOWN} 줄 가운데, 저장한 식별자 칸의 값이 이번 인자의 같은 칸 값과 같은 줄이
 * 있으면 다시 보내는 것이다. 판정은 그런 호출에 상시 허락과 대화 허락을 보지 않고 사람의 승인을 받게 한다.
 */
@Component
@RequiredArgsConstructor
class ConnectorUnknownResends {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ConnectorActionRepository actions;


    boolean resends(ConnectorConnection connection, String hermesTool, String argsJson) {
        JsonNode args;
        try {
            args = MAPPER.readTree(argsJson);
        } catch (JacksonException ex) {
            return false;
        }
        if (args == null || !args.isObject()) {
            return false;
        }
        return actions.findByUserIdAndConnectorIdAndHermesToolAndStatusAndStoredResultTextIsNotNull(
            connection.userId(), connection.connectorId(), hermesTool, ActionStatus.UNKNOWN)
            .stream()
            .map(ConnectorAction::resultText)
            .map(ConnectorUnknownDetail::fromStored)
            .flatMap(Optional::stream)
            .anyMatch(detail -> detail.retryKey() != null && sameValue(args.get(detail.retryKey()), detail.retryValue()));
    }


    private static boolean sameValue(JsonNode sent, String stored) {
        return sent != null && sent.isString() && sent.asString().equals(stored);
    }
}
