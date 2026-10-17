package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
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
     * 도구 정책이 하한보다 느슨한 커넥터는 받지 않는다.
     */
    static boolean accepted(ConnectorManifest manifest) {
        if (!ConnectorToolPolicies.valid(manifest)) {
            log.warn("connector {} declares a tool policy that cannot be judged", manifest.id());
            return false;
        }
        return true;
    }
}
