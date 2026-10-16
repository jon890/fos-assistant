package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 시작 트랜잭션이 커밋한 설치 입력이다. 엔티티와 lazy 참조, 가변 JSON을 외부 호출로 넘기지 않는다.
 *
 * <p>시도 UUID는 DB 결과 대조용이며 Hermes에 보내지 않는다.
 */
public record ConnectorBindingAttempt(
    Long bindingId,
    Long agentId,
    Long ownerId,
    Long connectionId,
    Instant connectionRevision,
    ConnectionStatus connectionStatus,
    boolean vaultStored,
    UUID attemptId,
    Instant startedAt,
    String profile,
    String apiBaseUrl,
    CredentialScope credentialScope,
    String sandboxOwner,
    String connectorId,
    String guardProtocol,
    String guardManifestSha256,
    String vault,
    String ownerBrowser,
    String mcpServer,
    boolean restartRequired,
    boolean checkInstallation,
    boolean replaceValues,
    ResyncOutcome waiting,
    List<String> declaredTools) {

    public ConnectorBindingAttempt {
        declaredTools = List.copyOf(declaredTools);
    }


    /** 비밀 칸과 브라우저 중계 표식을 문자열 표현에 싣지 않는다. */
    @Override
    public String toString() {
        return "ConnectorBindingAttempt[bindingId=" + bindingId + ", attemptId=" + attemptId + "]";
    }
}
