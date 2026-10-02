package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 plugin 의 커넥터 경로를 부른다.
 *
 * <p>커넥터의 이름과 env 이름과 서버 이름을 갖지 않는다. 모두 호출하는 쪽이 카탈로그에서 꺼내 준다(ADR-043).
 * 응답 모양이 틀리거나 호출이 실패하면 원문 없는 {@link IllegalStateException} 을 던진다.
 */
public interface HermesConnectorClient {
    /**
     * 그 profile 의 설치 상태다.
     *
     * @param policyHook 그 profile 의 도구 호출이 정책 판정을 거치는가. 대시보드가 참이라고 답했을 때만 참이다
     */
    record ConnectorState(
            String profile, boolean enabled, boolean configured, boolean restartRequired, boolean policyHook) {}

    /**
     * 설치 요청의 결과다.
     *
     * @param restartRequired 대시보드가 답한 재시작 필요. 설치된 연결에는 늘 참이다
     * @param pluginUpdated 설치가 그 profile 의 hook plugin 파일을 바꿨는가. 떠 있는 gateway 가 옛 코드를 쥐고 있을
     *     수 있다는 뜻이다. 옛 대시보드 plugin 은 이 칸을 내지 않고, 없으면 거짓이다
     */
    record InstallResult(boolean restartRequired, boolean pluginUpdated) {}

    record ProbeResult(boolean ok, List<String> tools) {}

    /** 운영 목록에 있고 검증을 통과한 커넥터의 manifest 다. 요청마다 대시보드에서 읽는다. */
    List<ConnectorManifest> readCatalog();

    /** 후보 값으로 선택지 도구나 확인 도구를 한 번 부른다. 대시보드는 값을 저장하지 않는다. */
    CallResult call(String connectorId, String tool, Map<String, String> values);

    /**
     * 승인한 호출을 한 번 실행한다. 결과를 알 수 없으면 {@link ConnectorExecutionUnknown} 을 던진다.
     *
     * <p>실행되지 않은 것이 분명한 거절은 실패 결과로 돌려준다. 받은 쪽은 어느 경우에도 다시 부르지 않는다.
     *
     * @param hermesTool 실행할 도구의 등록 이름
     * @param argsJson 승인한 인자의 JSON object 글. 값이 같은 JSON 으로 다시 써서 보낸다
     */
    CallResult execute(String profile, String connectorId, String hermesTool, String argsJson);

    InstallResult putConnector(String profile, String connectorId, boolean enabled);

    /** 그 profile 의 설치 상태다. 대시보드의 목록에 그 커넥터가 없으면 설치되지 않은 것으로 돌려준다. */
    ConnectorState readConnector(String profile, String connectorId);

    ProbeResult probe(String profile, String mcpServer);

    boolean putEnv(String profile, String key, String value);

    boolean deleteEnv(String profile, String key);
}
