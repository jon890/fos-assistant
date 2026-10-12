package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.connector.infra.ConnectorActionBodies;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 성공이 아닌 결과로 {@code EXECUTING} 승인 줄을 끝낸다(ADR-20261012 / financial-unsent-proof).
 *
 * <p>부르는 쪽은 사용자, 승인 줄, 실행 줄 순서로 이미 잠갔어야 한다. 실행 줄의 {@code consumed_at} 이 비어 있으면 claim 이
 * 성공한 적이 없고, 이 전이로 줄이 {@code EXECUTING} 밖으로 나가면 앞으로의 claim 도 거절되므로 금융 POST 는 나가지 않는다.
 * 실행 줄이 없는 줄(금융이 아닌 쓰기)은 지금처럼 결과를 모르는 것으로 끝낸다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorFinancialOutcome {

    /**
     * @param row 잠근 금융 실행 줄. 금융이 아닌 줄이면 null
     * @param result 실패 결과. 응답을 잃었으면 null. 성공 결과는 받지 않는다
     */
    static void settle(ConnectorAction action, ConnectorActionExecution row, CallResult result, ConnectorActionBodies bodies, Instant now) {
        if (action.status() != ActionStatus.EXECUTING) {
            throw new IllegalStateException("connector action must be EXECUTING but is " + action.status());
        }
        if (result != null && result.ok()) {
            throw new IllegalArgumentException("success result is recorded by the caller");
        }
        boolean consumed = row != null && row.consumedAt() != null;
        if (result == null) {
            if (row == null || consumed) {
                action.unknown(now);
            } else {
                bodies.fail(action, ConnectorCallError.UNAVAILABLE.word(), null, now);
            }
            return;
        }
        // claim 이 소비된 뒤의 상세 없는 unavailable 은 전송 전 거절이라는 계약과 어긋난다. 실패로 적지 않는다.
        if (consumed && result.error() == ConnectorCallError.UNAVAILABLE && result.detail() == null) {
            action.unknown(now);
            return;
        }
        bodies.fail(action, result.error().word(), result.detail() == null ? null : result.detail().toStored(), now);
    }
}
