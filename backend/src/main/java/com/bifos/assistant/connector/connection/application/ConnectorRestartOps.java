package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.application.dto.ConnectorRestartSnapshot;
import com.bifos.assistant.connector.connection.config.ConnectorOpsProperties;
import com.bifos.assistant.connector.connection.domain.RestartWaiting;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 운영 정기 작업이 공유 gateway 를 재시작할지 정하는 데 쓰는 수를 답한다(ADR-20261120 / connector-auto-apply, 스펙 REQ5).
 *
 * <p>재시작 여부, 6시간 상한, 재시작 뒤 확인은 운영 정기 작업이 정한다. Control Plane 은 재시작하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorRestartOps {
    private final ConnectorOpsProperties properties;
    private final ConnectorBindingRepository bindings;
    private final UserExecutionLimiter executions;


    /** 서비스 Bearer 토큰 하나를 시간 차이 없이 비교한다. 토큰이 설정되지 않았으면 늘 거절한다. */
    public void authenticate(String authorization) {
        String secret = properties.token();
        if (secret == null || secret.isBlank() || authorization == null || authorization.length() > 4096
            || !MessageDigest.isEqual(
                ("Bearer " + secret).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "connector ops authentication rejected");
        }
    }


    public ConnectorRestartSnapshot snapshot() {
        RestartWaiting waiting = bindings.summarizeRestartWaiting();
        return new ConnectorRestartSnapshot(waiting.count(), waiting.oldestSince(), executions.runningTotal());
    }
}
