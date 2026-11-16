package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.port.BrowserWaitDemand;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사용자 브라우저의 자동 중지가 묻는 「사람을 기다리는가」 를 판정 줄로 답한다. 뜻은 {@code docs/features/browser-cowork/spec.md}
 * 의 REQ1 과 설계의 「PR1 사람을 기다리는 동안 브라우저 유지」 가 갖는다.
 *
 * <p>카탈로그를 읽지 못했거나 카탈로그에 없는 플러그인의 기다리는 승인은 기다림으로 센다. 잘못 세어도 {@code wait-hold-timeout}
 * 뒤에는 꺼지고, 반대로 틀리면 승인하기 직전에 브라우저가 꺼진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBrowserWaitDemand implements BrowserWaitDemand {

    private final ConnectorActionRepository actions;
    private final ConnectorCatalogCache catalog;
    private final Clock clock;


    @Override
    public boolean waiting(long userId, Instant since) {
        if (actions.existsOpenHandoffSince(userId, since)) {
            return true;
        }
        return actions.findUnexpiredConnectorIdsByStatus(userId, ActionStatus.PENDING, clock.instant()).stream()
            .anyMatch(this::usesBrowserOrUnknown);
    }


    /** 그 플러그인이 「내 브라우저」 를 쓰는가. 읽지 못하면 쓰는 것으로 본다. 예외 메시지에는 원격 응답이 섞일 수 있어 종류만 남긴다. */
    private boolean usesBrowserOrUnknown(String connectorId) {
        try {
            return catalog.find(connectorId).map(manifest -> manifest.ownerBrowser()).orElse(true);
        } catch (RuntimeException ex) {
            log.warn("connector {} catalog read failed: {}", connectorId, ex.getClass().getSimpleName());
            return true;
        }
    }
}
