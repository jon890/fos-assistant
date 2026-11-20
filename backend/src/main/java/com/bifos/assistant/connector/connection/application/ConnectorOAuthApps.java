package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.application.dto.ConnectorOAuthAppStatus;
import com.bifos.assistant.crypto.domain.ServerSecretRecord;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration.AppScope;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 카탈로그가 선언한 OAuth 앱의 client secret 을 서버 비밀값에 두고 공개 상태를 읽는다. */
@Service
@RequiredArgsConstructor
public class ConnectorOAuthApps {
    // UTF-8로 감싼 뒤에도 server_secret.sealed_value 열에 들어가는 길이다.
    private static final int MAX_CREDENTIAL_LENGTH = 512;
    private static final String APP_NAME_PREFIX = "oauth_app:";
    private static final String CLIENT_ID = "clientId";
    private static final String APP_SLUG = "appSlug";

    private final ServerSecrets secrets;
    private final HermesConnectorClient connectors;
    private final Object writes = new Object();


    public boolean storeAvailable() {
        return secrets.available();
    }


    public List<ConnectorOAuthAppStatus> list() {
        Map<String, ConnectorManifest> apps = new LinkedHashMap<>();
        for (ConnectorManifest manifest : ConnectorManifests.read(connectors)) {
            if (manifest.oauth() != null) {
                apps.putIfAbsent(secretName(manifest), manifest);
            }
        }
        return apps.values().stream().map(this::status).toList();
    }


    public ConnectorOAuthAppStatus save(String connectorId, String clientId, String clientSecret, String appSlug, Long adminId) {
        ConnectorManifest manifest = declaredApp(connectorId)
            .orElseThrow(() -> new ApiException(ErrorCode.CONNECTOR_NOT_FOUND, "connector has no OAuth app"));
        Map<String, Object> metadata = Map.of(CLIENT_ID, credential(clientId));
        if (manifest.oauth().appScope() == AppScope.PLUGIN) {
            if (appSlug == null || !appSlug.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")) {
                throw ConnectorErrors.invalid();
            }
            metadata = Map.of(CLIENT_ID, clientId, APP_SLUG, appSlug);
        }
        String secret = credential(clientSecret);
        // 없는 줄에 두 요청이 동시에 쓰더라도 한 요청의 저장이 끝난 뒤 다음 요청이 읽는다.
        synchronized (writes) {
            secrets.write(secretName(manifest), secret, metadata, adminId);
            return status(manifest);
        }
    }


    public void clear(String connectorId, Long adminId) {
        Optional<ConnectorManifest> manifest = declaredApp(connectorId);
        synchronized (writes) {
            String name = manifest.map(ConnectorOAuthApps::secretName).orElseGet(() -> previousPluginApp(connectorId));
            secrets.clear(name, adminId);
        }
    }


    private Optional<ConnectorManifest> declaredApp(String connectorId) {
        return ConnectorManifests.find(connectors, connectorId)
            .filter(manifest -> manifest.oauth() != null);
    }


    /** 카탈로그에서 빠진 플러그인은 그 플러그인 명의로 이미 저장된 앱만 비울 수 있다. */
    private String previousPluginApp(String connectorId) {
        String name = APP_NAME_PREFIX + "github:" + connectorId;
        if (!connectorId.matches("[a-z0-9][a-z0-9-]{0,63}") || !secrets.exists(name)) {
            throw new ApiException(ErrorCode.CONNECTOR_NOT_FOUND, "connector has no OAuth app");
        }
        return name;
    }


    private ConnectorOAuthAppStatus status(ConnectorManifest manifest) {
        ServerSecretRecord row = secrets.find(secretName(manifest)).orElse(null);
        Map<String, Object> metadata = row == null ? Map.of() : row.meta();
        return new ConnectorOAuthAppStatus(
            manifest.id(), manifest.title(), manifest.oauth().provider(),
            text(metadata, CLIENT_ID), text(metadata, APP_SLUG),
            row != null && row.set(), row == null ? null : row.prefix(),
            row == null ? null : row.updatedBy(), row == null ? null : row.updatedAt());
    }


    private static String secretName(ConnectorManifest manifest) {
        String prefix = APP_NAME_PREFIX + manifest.oauth().provider();
        return manifest.oauth().appScope() == AppScope.PLUGIN ? prefix + ":" + manifest.id() : prefix;
    }


    private static String credential(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CREDENTIAL_LENGTH
            || value.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            throw ConnectorErrors.invalid();
        }
        return value;
    }


    private static String text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String text ? text : null;
    }
}
