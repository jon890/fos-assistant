package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.util.List;

/**
 * 카탈로그의 커넥터 하나와 그것에 대한 내 연결 상태다.
 *
 * @param myStatus 등록한 적이 없으면 {@code DISCONNECTED}
 * @param available 지금 카탈로그에 있는가. 거짓이면 읽기와 해제만 되고 {@code fields} 와 {@code description} 이 비어 있다
 */
public record ConnectorSummary(
        String id,
        String title,
        String description,
        List<ConnectorFieldSummary> fields,
        ConnectionStatus myStatus,
        boolean available) {}
