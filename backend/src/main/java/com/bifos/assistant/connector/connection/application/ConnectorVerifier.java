package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.connector.connection.application.dto.ConnectorVerified;
import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ListedTools;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.VaultAccess;
import com.bifos.assistant.hermes.application.dto.ConnectorToolListing;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 연결의 값이 쓸 수 있는지 외부를 불러 확인한다. DB 트랜잭션 밖에서 부른다.
 *
 * <p>확인 도구를 선언한 커넥터는 그 도구를 부른다. 확인 도구가 없는 표준 플러그인({@code schema: 3})은 그 MCP 서버에 붙어
 * {@code tools/list} 가 성공하는지로 확인하고 받은 도구와 읽기 표시를 돌려준다(ADR-20261101 / plugin-without-assistant-json).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorVerifier {
    private static final String STEP_TOOL_CALL = "tool-call";
    private static final String STEP_TOOL_LIST = "tool-list";

    private final HermesConnectorClient connector;
    private final BrowserGatewayTokens tokens;


    /** 후보 값으로 확인한다. 실패하면 공통 어휘에 맞는 오류로 끝낸다. 표준 플러그인이면 받은 도구 목록을, 아니면 null 을 돌려준다. */
    ListedTools values(long userId, ConnectorManifest manifest, Map<String, String> values) {
        ConnectorVerified verified = manifest.schema() == ConnectorToolPolicies.STANDARD_SCHEMA
            ? listed(manifest, () -> connector.listTools(manifest.id(), values))
            : called(manifest, () -> manifest.verifyTool() == null ? null
                : connector.call(manifest.id(), manifest.verifyTool(), values, ownerBrowser(userId, manifest)));
        if (verified.failure() != null) {
            throw verified.failure();
        }
        return verified.listed();
    }


    /**
     * 보관 파일의 값으로 확인한다. 값은 Control Plane 을 거치지 않는다. 실패는 오류로 돌려준다.
     *
     * @param oauth 단추 연결이면 확인 도구가 토큰을 받을 자리. 돌아온 흐름은 대기 자리, 이미 있는 연결은 본 자리다. 붙여 넣은 연결이면 null
     */
    public ConnectorVerified vault(long userId, ConnectorManifest manifest, String vault, VaultAccess oauth) {
        if (manifest.schema() == ConnectorToolPolicies.STANDARD_SCHEMA) {
            return listed(manifest, () -> connector.listToolsWithVault(manifest.id(), vault));
        }
        return called(manifest, () -> manifest.verifyTool() == null ? null
            : called(manifest.id(), manifest.verifyTool(), vault, ownerBrowser(userId, manifest), oauth));
    }


    private CallResult called(String connectorId, String tool, String vault, String ownerBrowser, VaultAccess oauth) {
        return oauth == null
            ? connector.callWithVault(connectorId, tool, vault, ownerBrowser)
            : connector.callWithVault(connectorId, tool, vault, ownerBrowser, oauth);
    }


    /**
     * 단추 연결을 시작할 때 함께 받은 단추 밖 칸 값을 등록과 같이 검사하고 채운 칸만 남긴다. 위 하위 기능 {@code connector.oauth} 가 부른다.
     */
    public Map<String, String> oauthValues(ConnectorManifest manifest, Map<String, String> values, ConnectionAuth auth) {
        return ConnectorValues.validated(manifest, values, auth);
    }


    /** 단추 연결이 만들어지면 저장할 칸 값이다. 비밀 칸은 앞부분만 남는다. */
    public ConnectionFields oauthFields(ConnectorManifest manifest, Map<String, String> accepted, ConnectionAuth auth) {
        return ConnectorValues.stored(manifest, accepted, auth);
    }


    /**
     * 확인 도구와 선택지 호출에 실을 브라우저 중계 주소다. 사용자 브라우저를 쓰지 않는 커넥터는 null 이라 본문에 키가 없다.
     *
     * <p>바인딩이 없는 호출이라 요청자의 호출 표식을 싣는다. 중계가 꺼졌으면 빈 값이다(ADR-20261008 / browser-gateway-token).
     */
    String ownerBrowser(long userId, ConnectorManifest manifest) {
        return manifest.ownerBrowser() ? tokens.callAddress(userId).orElse("") : null;
    }


    /** @param call 확인 도구를 부른다. 확인 도구가 없으면 null 을 돌려준다 */
    private static ConnectorVerified called(ConnectorManifest manifest, Supplier<CallResult> call) {
        final CallResult result;
        try {
            result = call.get();
        } catch (RuntimeException ex) {
            warn(STEP_TOOL_CALL, manifest.id(), ex);
            return new ConnectorVerified(ConnectorErrors.unavailable(), null);
        }
        if (result == null) {
            return new ConnectorVerified(ConnectorErrors.unavailable(), null);
        }
        return new ConnectorVerified(result.ok() ? null : ConnectorErrors.of(result.error()), null);
    }


    private static ConnectorVerified listed(ConnectorManifest manifest, Supplier<ConnectorToolListing> list) {
        final ConnectorToolListing listing;
        try {
            listing = list.get();
        } catch (RuntimeException ex) {
            warn(STEP_TOOL_LIST, manifest.id(), ex);
            return new ConnectorVerified(ConnectorErrors.unavailable(), null);
        }
        if (!listing.ok()) {
            return new ConnectorVerified(ConnectorErrors.of(listing.error()), null);
        }
        return new ConnectorVerified(null, new ListedTools(listing.tools()));
    }


    /** 실패한 단계와 커넥터 번호와 예외 종류만 남긴다. 예외 메시지와 원격 응답에는 칸 값이 섞일 수 있어 적지 않는다. */
    static void warn(String step, String connectorId, RuntimeException ex) {
        log.warn("connector {} failed at {}: {}", connectorId, step, ex.getClass().getSimpleName());
    }
}
