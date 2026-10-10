package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 카탈로그가 낸 manifest 가운데 Control Plane 이 받는 것을 고른다.
 *
 * <p>화면 경로와 도구 호출 판정 경로가 같은 조건을 쓴다. 조건이 갈리면 화면에는 없는 커넥터의 호출을 판정하거나 그
 * 반대가 된다.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorManifests {

    /**
     * 카탈로그를 요청마다 다시 읽고 받는 manifest 만 남긴다. 운영자가 목록을 바꾸면 화면에 바로 보여야 하기 때문이다.
     *
     * @throws com.bifos.assistant.shared.error.ApiException {@code CONNECTOR_UNAVAILABLE}. 카탈로그를 읽지 못했을 때
     */
    static List<ConnectorManifest> read(HermesConnectorClient connector) {
        final List<ConnectorManifest> manifests;
        try {
            manifests = connector.readCatalog();
        } catch (RuntimeException ex) {
            log.warn("connector catalog read failed: {}", ex.getClass().getSimpleName());
            throw ConnectorErrors.unavailable();
        }
        return manifests.stream().filter(ConnectorManifests::accepted).toList();
    }

    /** 그 번호의 manifest 다. 카탈로그에 없거나 받지 않는 선언이면 빈 값이다. */
    public static Optional<ConnectorManifest> find(HermesConnectorClient connector, String connectorId) {
        return read(connector).stream()
                .filter(manifest -> manifest.id().equals(connectorId))
                .findFirst();
    }

    /**
     * manifest 로 열 수 없는 내장 toolset 을 선언한 커넥터와 도구 정책이 하한보다 느슨한 커넥터는 받지 않는다.
     *
     * <p>대시보드가 같은 검사를 먼저 한다. 여기서 한 번 더 보는 것은 셸이나 파일 도구가 대시보드의 결함으로 넘어와도
     * 연결용 에이전트에 켜지지 않게 하고(ADR-044), 느슨한 정책으로 호출을 판정하지 않기 위해서다(ADR-049).
     */
    static boolean accepted(ConnectorManifest manifest) {
        if (!AgentToolPolicy.allowedForConnector(manifest.toolsets())
                || (manifest.attachments() && !manifest.toolsets().contains(AgentToolPolicy.VISION))) {
            log.warn("connector {} declares toolsets a manifest cannot open", manifest.id());
            return false;
        }
        if (!ConnectorToolPolicies.valid(manifest)) {
            log.warn("connector {} declares a tool policy that cannot be judged", manifest.id());
            return false;
        }
        return true;
    }
}
