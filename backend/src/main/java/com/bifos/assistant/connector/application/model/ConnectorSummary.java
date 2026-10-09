package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.hermes.dto.ConnectorAppearance;
import java.util.List;

/**
 * 카탈로그의 커넥터 하나와 그것에 대한 내 연결 상태다.
 *
 * @param appearance 카드의 아이콘과 링크. 카탈로그에서 빠진 커넥터는 {@link ConnectorAppearance#NONE}
 * @param tools 도구마다의 위험도와 승인 방식. 도구를 선언하지 않는 판의 커넥터와 카탈로그에서 빠진 커넥터는 빈 목록
 * @param myStatus 등록한 적이 없으면 {@code DISCONNECTED}
 * @param available 지금 카탈로그에 있는가. 거짓이면 읽기와 해제만 되고 {@code fields} 와 {@code description} 이 비어 있다
 * @param bindings 내 연결이 붙은 에이전트들. 연결이 없으면 빈 목록
 * @param ownerBrowserLoginUrl 사용자 브라우저에서 먼저 로그인할 곳. 선언하지 않았거나 카탈로그에서 빠진 커넥터는 null
 */
public record ConnectorSummary(
        String id,
        String title,
        String description,
        ConnectorAppearance appearance,
        List<ConnectorFieldSummary> fields,
        List<ConnectorToolSummary> tools,
        ConnectionStatus myStatus,
        boolean available,
        List<BoundAgentSummary> bindings,
        String ownerBrowserLoginUrl) {

    public ConnectorSummary {
        bindings = List.copyOf(bindings);
    }

    /** 로그인 안내 주소가 없는 항목이다. */
    public ConnectorSummary(
            String id,
            String title,
            String description,
            ConnectorAppearance appearance,
            List<ConnectorFieldSummary> fields,
            List<ConnectorToolSummary> tools,
            ConnectionStatus myStatus,
            boolean available,
            List<BoundAgentSummary> bindings) {
        this(id, title, description, appearance, fields, tools, myStatus, available, bindings, null);
    }
}
