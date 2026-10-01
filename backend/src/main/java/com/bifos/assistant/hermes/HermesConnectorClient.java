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
    record ConnectorState(String profile, boolean enabled, boolean configured, boolean restartRequired) {}

    record ProbeResult(boolean ok, List<String> tools) {}

    /** 운영 목록에 있고 검증을 통과한 커넥터의 manifest 다. 요청마다 대시보드에서 읽는다. */
    List<ConnectorManifest> readCatalog();

    /** 후보 값으로 선택지 도구나 확인 도구를 한 번 부른다. 대시보드는 값을 저장하지 않는다. */
    CallResult call(String connectorId, String tool, Map<String, String> values);

    boolean putConnector(String profile, String connectorId, boolean enabled);

    ConnectorState readConnector(String profile, String connectorId);

    ProbeResult probe(String profile, String mcpServer);

    boolean putEnv(String profile, String key, String value);

    boolean deleteEnv(String profile, String key);
}
