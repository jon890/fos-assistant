package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.browser.application.port.BrowserApprovedRuns;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사람이 브라우저를 넘겨받기 전에 묻는 「브라우저를 쓰는 승인한 실행이 도는가」 를 승인 줄로 답한다. 뜻은
 * {@code docs/features/browser-cowork/spec.md} 의 REQ6 과 설계의 「PR3 승인과 회수」 가 갖는다.
 *
 * <p>금융·구매 승인을 지나 실행 중({@code EXECUTING})인 줄만 본다. 원고 저장은 위험도가 일반 쓰기라 들지 않는다. 카탈로그를 읽지 못한
 * 플러그인은 브라우저를 쓰는 것으로 본다. 잘못 막아도 실행이 끝나면 풀린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBrowserApprovedRuns implements BrowserApprovedRuns {

    private static final List<ToolRisk> APPROVED_RUNS = List.of(ToolRisk.FINANCIAL, ToolRisk.PURCHASE);

    private final ConnectorActionRepository actions;
    private final ConnectorCatalogCache catalog;


    @Override
    public boolean running(long userId) {
        return actions.findConnectorIdsByStatusAndRiskIn(userId, ActionStatus.EXECUTING, APPROVED_RUNS).stream()
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
