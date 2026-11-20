package com.bifos.assistant.connector.connection.application.dto;

import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 시작 트랜잭션이 커밋한 설치 입력이다. 엔티티와 lazy 참조, 가변 JSON을 외부 호출로 넘기지 않는다.
 *
 * <p>시도 UUID는 DB 결과 대조용이며 Hermes에 보내지 않는다.
 *
 * @param undeclaredAsk 선언하지 않은 도구를 호출마다 묻는 커넥터({@code default_tool_policy: ask})인가. 참이면 선언하지 않은 도구 수를 세지 않는다
 * @param automatic 사람이 아닌 예약 작업이 시작한 시도인가. 자동 시도의 실패는 바인딩에 실패 표시를 남긴다(ADR-20261120 / connector-auto-apply)
 * @param resumedFailure 사람이 자동 반영이 실패한 바인딩을 다시 이은 시도인가. 이 시도도 실패하면 실패 표시를 다시 남겨 사용자 화면이 실패를
 * 계속 보인다
 * @param cycleStep 껐다 켜기의 걸음
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
    List<String> declaredTools,
    boolean undeclaredAsk,
    boolean automatic,
    boolean resumedFailure,
    CycleStep cycleStep) {

    public ConnectorBindingAttempt {
        declaredTools = List.copyOf(declaredTools);
    }


    /** 비밀 칸과 브라우저 중계 표식을 문자열 표현에 싣지 않는다. */
    @Override
    public String toString() {
        return "ConnectorBindingAttempt[bindingId=" + bindingId + ", attemptId=" + attemptId + "]";
    }
}
