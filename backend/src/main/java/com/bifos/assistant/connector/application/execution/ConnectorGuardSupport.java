package com.bifos.assistant.connector.application.execution;

import com.bifos.assistant.connector.application.ConnectorManifests;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 현재 바인딩과 새 카탈로그의 보호 선언을 대조한다. 지원 확인은 거래 권한을 주지 않는다. */
@Service
@RequiredArgsConstructor
public class ConnectorGuardSupport {
    private final ConnectorBindingRepository bindings;
    private final UserAccessPolicy access;
    private final HermesConnectorClient hermes;
    private final HermesProperties properties;
    private final ConnectorExecutionGuard guard;
    private final PlatformTransactionManager transactionManager;


    /** JWT, profile 토큰, ticket을 해석하지 않고 고정 서비스 Bearer만 상수 시간으로 대조한다. */
    public void authenticate(String authorization) {
        String secret = properties.dashboardToken();
        if (secret == null || secret.isBlank() || authorization == null || authorization.length() > 4096
            || !MessageDigest.isEqual(
                ("Bearer " + secret).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "connector support authentication rejected");
        }
    }


    /** HTTP 전후의 DB 상태가 같고 현재 manifest 전체 SHA가 일치할 때만 지원을 확인한다. */
    public void verify(Long bindingId, Long connectionId, Instant revision, String profile,
        String connectorId, String protocol, String manifestSha256) {
        ConnectorExecutionCurrent.requireNoTransaction();
        try {
            Context before = read(bindingId);
            require(Objects.equals(connectionId, before.connectionId())
                && revision.equals(before.connectionUpdatedAt())
                && profile.equals(before.profile()) && connectorId.equals(before.connectorId()));
            var manifest = ConnectorManifests.find(hermes, before.connectorId()).orElseThrow(ConnectorGuardSupport::rejected);
            require(manifest.schema() == 2 && before.mcpServer().equals(manifest.mcpServer())
                && protocol.equals(guard.read(manifest).protocol())
                && manifestSha256.equals(manifest.executionGuardManifestSha256()));
            // HTTP가 진행되는 동안 철회되거나 설치 상태가 바뀐 바인딩은 확인하지 않는다.
            require(before.equals(read(bindingId)));
        } catch (RuntimeException ignored) {
            throw rejected();
        }
    }


    private Context read(Long bindingId) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            var binding = bindings.findById(bindingId).orElseThrow(ConnectorGuardSupport::rejected);
            var connection = binding.connection();
            var agent = binding.agent();
            require(binding.status() == BindingStatus.READY && binding.desiredEnabled()
                && !binding.restartRequired() && connection.status() == ConnectionStatus.READY
                && agent.enabled() && Objects.equals(connection.userId(), agent.ownerUserId())
                && access.allowed(connection.userId()));
            return new Context(connection.id(), connection.userId(), agent.id(), agent.hermesProfile(),
                connection.connectorId(), binding.mcpServer(), connection.updatedAt(), binding.updatedAt());
        });
    }


    private static void require(boolean condition) {
        if (!condition) {
            throw rejected();
        }
    }


    private static ApiException rejected() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "connector support unavailable");
    }


    private record Context(Long connectionId, Long userId, Long agentId, String profile, String connectorId,
        String mcpServer, Instant connectionUpdatedAt, Instant bindingUpdatedAt) {
    }
}
