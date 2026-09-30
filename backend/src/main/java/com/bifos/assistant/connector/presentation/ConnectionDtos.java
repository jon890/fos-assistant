package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.ConnectionSnapshot;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public final class ConnectionDtos {
    private ConnectionDtos() {}
    public record RegisterRequest(@NotBlank String token, String familyUuid) {
        @Override public String toString() { return "RegisterRequest[token=[REDACTED]]"; }
    }
    public record ConnectionView(String status, String tokenPrefix, String familyUuid, boolean restartRequired,
            Instant checkedAt, String agentCode) {
        static ConnectionView from(ConnectionSnapshot value) { return new ConnectionView(value.status().name(), value.tokenPrefix(), value.familyUuid() == null ? null : value.familyUuid().toString(), value.restartRequired(), value.checkedAt(), value.agentCode()); }
    }
    public record AdminConnectionView(Long userId, String displayName, String status, String agentCode, boolean restartRequired) {
        static AdminConnectionView from(AdminConnectionSnapshot value) { return new AdminConnectionView(value.userId(), value.displayName(), value.status().name(), value.agentCode(), value.restartRequired()); }
    }
}
