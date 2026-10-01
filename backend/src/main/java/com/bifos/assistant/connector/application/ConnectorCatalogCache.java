package com.bifos.assistant.connector.application;

import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 도구 호출 판정이 쓰는 카탈로그를 잠시 메모리에 둔다(ADR-049).
 *
 * <p>판정은 도구 호출마다 오므로 그때마다 대시보드를 읽지 않는다. 화면 경로는 이 값을 쓰지 않고 요청마다 다시 읽는다.
 * 운영자가 목록을 바꾼 것이 화면에는 바로 보여야 하기 때문이다. 상태는 JVM 메모리에 둔다. Control Plane 이 한 대라는
 * 전제다.
 */
@Component
public class ConnectorCatalogCache {
    private final HermesConnectorClient connector;
    private final ConnectorPolicyProperties properties;
    private final Clock clock;

    private List<ConnectorManifest> manifests = List.of();
    private Instant readAt;
    private RuntimeException failure;
    private Instant failedAt;

    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorCatalogCache(HermesConnectorClient connector, ConnectorPolicyProperties properties) {
        this(connector, properties, Clock.systemUTC());
    }

    public ConnectorCatalogCache(HermesConnectorClient connector, ConnectorPolicyProperties properties, Clock clock) {
        this.connector = connector;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 그 번호의 manifest 다. 카탈로그에 없거나 Control Plane 이 받지 않는 선언이면 빈 값이다.
     *
     * <p>마지막으로 읽은 지 보관 시간이 지났으면 다시 읽는다. 읽다가 난 예외는 그대로 던지고 옛 값으로 답하지 않는다.
     * 옛 정책으로 판정하면 운영자가 막은 도구가 통과할 수 있다. 여럿이 동시에 와도 한 번만 읽도록 잠근다.
     *
     * <p>읽기 실패는 잠시 기억한다. 그동안은 대시보드를 다시 부르지 않고 그 예외를 바로 던진다. 대시보드가 응답하지
     * 않을 때 판정마다 잠금을 쥔 채 응답 시간 한도까지 기다리면 뒤의 판정이 줄줄이 밀린다.
     */
    public synchronized Optional<ConnectorManifest> find(String connectorId) {
        Instant now = Instant.now(clock);
        if (readAt == null || !now.isBefore(readAt.plus(properties.catalogTtl()))) {
            if (failure != null && now.isBefore(failedAt.plus(properties.catalogFailureTtl()))) {
                throw failure;
            }
            try {
                manifests = connector.readCatalog().stream()
                        .filter(ConnectorManifests::accepted)
                        .toList();
            } catch (RuntimeException ex) {
                failure = ex;
                failedAt = now;
                throw ex;
            }
            failure = null;
            readAt = now;
        }
        return manifests.stream()
                .filter(manifest -> manifest.id().equals(connectorId))
                .findFirst();
    }
}
