package com.bifos.assistant.connector.oauth.application.dto;

import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;

/**
 * 갱신의 결과다.
 *
 * @param token {@code OK} 일 때만 있다
 */
public record ConnectorOAuthRefresh(Outcome outcome, ConnectorOAuthToken token) {
}
