package com.bifos.assistant.connector.presentation.execution;

import com.bifos.assistant.connector.application.model.ConnectorExecutionClaimResult;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** claim HTTP 응답 모양이다. 요청은 원문을 strict codec으로 읽는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorExecutionClaimDtos {
    public record ClaimResponse(int v, boolean allowed, UUID ticketId, Instant expiresAt) {
        public static ClaimResponse from(ConnectorExecutionClaimResult result) {
            return new ClaimResponse(result.v(), result.allowed(), result.ticketId(), result.expiresAt());
        }
    }
}
