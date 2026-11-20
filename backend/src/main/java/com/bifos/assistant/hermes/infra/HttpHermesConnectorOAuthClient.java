package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Authorization;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Credentials;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Exchange;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.port.ConnectorOAuthFlowConflict;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** 대시보드의 서비스 토큰 경계다. 원격 오류 본문은 응답 해석이나 예외 메시지에 쓰지 않는다. */
@Component
public class HttpHermesConnectorOAuthClient implements HermesConnectorOAuthClient {
    private static final String VAULT = "vault";
    private static final String CONNECTOR = "connector";
    private static final String NONCE = "nonce";
    private static final String CLIENT_ID = "client_id";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REDIRECT_URI = "redirect_uri";
    private static final String TOKEN_URL = "token_url";

    private final RestClient client;
    private final String baseUrl;
    private final String dashboardToken;


    public HttpHermesConnectorOAuthClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        client = RestClient.builder().requestFactory(factory).build();
        baseUrl = properties.dashboardBaseUrl().replaceAll("/$", "");
        dashboardToken = properties.dashboardToken();
    }


    @Override
    public String authorizeUrl(Authorization request) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
            CONNECTOR, request.connectorId(),
            "mode", request.mode().word(),
            CLIENT_ID, request.clientId(),
            REDIRECT_URI, request.redirectUri(),
            "state", request.state()));
        optional(body, "app_slug", request.appSlug());
        optional(body, "code_challenge", request.codeChallenge());
        return requiredText(post("authorize-url", body), "url");
    }


    @Override
    public String begin(String vault, String connectorId, Map<String, String> values) {
        return requiredText(post("begin", Map.of(VAULT, vault, CONNECTOR, connectorId, "values", values)), NONCE);
    }


    @Override
    public Token exchange(Exchange request) {
        Map<String, Object> body = appBody(request.vault(), request.connectorId(), request.credentials());
        body.put(NONCE, request.nonce());
        body.put("code", request.code());
        body.put(REDIRECT_URI, request.redirectUri());
        body.put(TOKEN_URL, request.tokenUrl());
        optional(body, "code_verifier", request.codeVerifier());
        return token(post("exchange", body));
    }


    @Override
    public void commit(String vault, String nonce, String tokenUrl) {
        requireOk(post("commit", Map.of(VAULT, vault, NONCE, nonce, TOKEN_URL, tokenUrl)));
    }


    @Override
    public void discard(String vault, String connectorId, String nonce, Credentials credentials, String accessToken) {
        Map<String, Object> body = appBody(vault, connectorId, credentials);
        body.put(NONCE, nonce);
        optional(body, ACCESS_TOKEN, accessToken);
        requireOk(post("discard", body));
    }


    @Override
    public Token refresh(String vault, String connectorId, Credentials credentials) {
        return token(post("refresh", appBody(vault, connectorId, credentials)));
    }


    @Override
    public Outcome revoke(String vault, String connectorId, Credentials credentials, String accessToken) {
        Map<String, Object> body = appBody(vault, connectorId, credentials);
        optional(body, ACCESS_TOKEN, accessToken);
        return outcome(post("revoke", body));
    }


    @Override
    public Outcome replaceTokenUrl(String vault, String tokenUrl) {
        return outcome(post("token-url", Map.of(VAULT, vault, TOKEN_URL, tokenUrl)));
    }


    private JsonNode post(String action, Map<String, Object> body) {
        try {
            ResponseEntity<JsonNode> response = client.post()
                .uri(baseUrl + "/api/connector-oauth/" + action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dashboardToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(JsonNode.class);
            JsonNode payload = response.getBody();
            return response.getStatusCode().is2xxSuccessful() && payload != null && payload.isObject() ? payload : null;
        } catch (HttpClientErrorException.Conflict ex) {
            throw new ConnectorOAuthFlowConflict();
        } catch (RestClientException ex) {
            return null;
        }
    }


    private static Map<String, Object> appBody(String vault, String connectorId, Credentials credentials) {
        return new LinkedHashMap<>(Map.of(
            VAULT, vault, CONNECTOR, connectorId, CLIENT_ID, credentials.clientId(), "client_secret", credentials.clientSecret()));
    }


    private static void optional(Map<String, Object> body, String field, String value) {
        if (value != null) {
            body.put(field, value);
        }
    }


    private static Token token(JsonNode body) {
        Outcome result = outcome(body);
        if (result != Outcome.OK) {
            return new Token(result, null, null);
        }
        String accessToken = text(body, ACCESS_TOKEN);
        JsonNode expiry = body.get("expires_at");
        if (accessToken == null || expiry == null
            || (!expiry.isNull() && (!expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.longValue() <= 0))) {
            return new Token(Outcome.UNAVAILABLE, null, null);
        }
        try {
            Instant expiresAt = expiry.isNull() ? null : Instant.ofEpochSecond(expiry.longValue());
            return new Token(Outcome.OK, accessToken, expiresAt);
        } catch (DateTimeException ex) {
            return new Token(Outcome.UNAVAILABLE, null, null);
        }
    }


    private static Outcome outcome(JsonNode body) {
        return switch (text(body, "outcome")) {
            case "ok" -> Outcome.OK;
            case "grant_revoked" -> Outcome.GRANT_REVOKED;
            case "app_rejected" -> Outcome.APP_REJECTED;
            case "scope_denied" -> Outcome.SCOPE_DENIED;
            case "missing" -> Outcome.MISSING;
            case null, default -> Outcome.UNAVAILABLE;
        };
    }


    private static void requireOk(JsonNode body) {
        JsonNode value = body == null ? null : body.get("ok");
        if (value == null || !value.isBoolean() || !value.booleanValue()) {
            throw new IllegalStateException("OAuth dashboard request failed");
        }
    }


    private static String requiredText(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalStateException("OAuth dashboard request failed");
        }
        return value;
    }


    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
