package com.bifos.assistant.hermes.application.port;

import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Authorization;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Credentials;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Exchange;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import java.util.Map;

/** OAuth 파일은 대시보드가 관리하고 이 경로는 refresh token 을 받지 않는다. */
public interface HermesConnectorOAuthClient {
    String authorizeUrl(Authorization request);


    String begin(String vault, String connectorId, Map<String, String> values);


    Token exchange(Exchange request);


    void commit(String vault, String nonce, String tokenUrl);


    void discard(String vault, String connectorId, String nonce, Credentials credentials, String accessToken);


    Token refresh(String vault, String connectorId, Credentials credentials);


    Outcome revoke(String vault, String connectorId, Credentials credentials, String accessToken);


    Outcome replaceTokenUrl(String vault, String tokenUrl);
}
