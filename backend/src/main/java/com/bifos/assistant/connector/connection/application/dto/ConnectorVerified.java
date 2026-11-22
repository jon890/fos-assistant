package com.bifos.assistant.connector.connection.application.dto;

import com.bifos.assistant.connector.connection.domain.ListedTools;
import com.bifos.assistant.shared.error.ApiException;

/**
 * 연결 확인의 결과다.
 *
 * @param failure 확인하지 못한 까닭. 확인했으면 null
 * @param listed 표준 플러그인이 받은 도구 목록. 표준 플러그인이 아니거나 확인하지 못했으면 null
 */
public record ConnectorVerified(ApiException failure, ListedTools listed) {
}
