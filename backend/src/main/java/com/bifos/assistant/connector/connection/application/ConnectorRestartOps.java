package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.application.dto.ConnectorRestartSnapshot;
import com.bifos.assistant.connector.connection.config.ConnectorOpsProperties;
import com.bifos.assistant.connector.connection.domain.RestartWaiting;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import com.bifos.assistant.user.application.port.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final AppUserRepository users;
    private final SignInRevocation access;


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


    /**
     * 재시작 감지가 풀 수 있는 대기만 센다. 연결 사용자가 없거나 접근이 철회된 대기는 재시작 감지가 사용자 잠금에서 거절해 재시작으로 풀리지
     * 않는다. 세면 운영 작업이 재시작을 되풀이한다.
     */
    public ConnectorRestartSnapshot snapshot() {
        List<RestartWaiting> rows = bindings.findRestartWaitingOwners();
        Set<Long> active = users.findAllById(rows.stream().map(RestartWaiting::userId).collect(Collectors.toSet())).stream()
            .filter(user -> !access.revoked(user.email()))
            .map(AppUser::id)
            .collect(Collectors.toSet());
        List<Instant> waiting = rows.stream()
            .filter(row -> active.contains(row.userId()))
            .map(RestartWaiting::since)
            .filter(Objects::nonNull)
            .toList();
        Instant oldest = waiting.stream().min(Comparator.naturalOrder()).orElse(null);
        return new ConnectorRestartSnapshot(waiting.size(), oldest, executions.runningTotal());
    }
}
