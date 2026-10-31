package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.connector.connection.domain.ListedTools;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorToolListing;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.shared.error.ApiException;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 연결의 값이 쓸 수 있는지 외부를 불러 확인한다. DB 트랜잭션 밖에서 부른다.
 *
 * <p>확인 도구를 선언한 커넥터는 그 도구를 부른다. 확인 도구가 없는 표준 플러그인({@code schema: 3})은 그 MCP 서버에 붙어
 * {@code tools/list} 가 성공하는지로 확인하고 받은 도구와 읽기 표시를 돌려준다(ADR-20261101 / plugin-without-assistant-json).
 */
@Slf4j
final class ConnectorVerifier {
    private static final String STEP_TOOL_CALL = "tool-call";
    private static final String STEP_TOOL_LIST = "tool-list";

    private final HermesConnectorClient connector;
    private final BrowserGatewayTokens tokens;


    /**
     * 연결 확인의 결과다.
     *
     * @param failure 확인하지 못한 까닭. 확인했으면 null
     * @param listed 표준 플러그인이 받은 도구 목록. 표준 플러그인이 아니거나 확인하지 못했으면 null
     */
    record Verified(ApiException failure, ListedTools listed) {
    }


    ConnectorVerifier(HermesConnectorClient connector, BrowserGatewayTokens tokens) {
        this.connector = connector;
        this.tokens = tokens;
    }


    /** 후보 값으로 확인한다. 실패하면 공통 어휘에 맞는 오류로 끝낸다. 표준 플러그인이면 받은 도구 목록을, 아니면 null 을 돌려준다. */
    ListedTools values(long userId, ConnectorManifest manifest, Map<String, String> values) {
        Verified verified = manifest.schema() == ConnectorToolPolicies.STANDARD_SCHEMA
            ? listed(manifest, () -> connector.listTools(manifest.id(), values))
            : called(manifest, () -> manifest.verifyTool() == null ? null
                : connector.call(manifest.id(), manifest.verifyTool(), values, ownerBrowser(userId, manifest)));
        if (verified.failure() != null) {
            throw verified.failure();
        }
        return verified.listed();
    }


    /** 보관 파일의 값으로 확인한다. 값은 Control Plane 을 거치지 않는다. 실패는 오류로 돌려준다. */
    Verified vault(long userId, ConnectorManifest manifest, String vault) {
        if (manifest.schema() == ConnectorToolPolicies.STANDARD_SCHEMA) {
            return listed(manifest, () -> connector.listToolsWithVault(manifest.id(), vault));
        }
        return called(manifest, () -> manifest.verifyTool() == null ? null
            : connector.callWithVault(manifest.id(), manifest.verifyTool(), vault, ownerBrowser(userId, manifest)));
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
    private static Verified called(ConnectorManifest manifest, Supplier<CallResult> call) {
        final CallResult result;
        try {
            result = call.get();
        } catch (RuntimeException ex) {
            warn(STEP_TOOL_CALL, manifest.id(), ex);
            return new Verified(ConnectorErrors.unavailable(), null);
        }
        if (result == null) {
            return new Verified(ConnectorErrors.unavailable(), null);
        }
        return new Verified(result.ok() ? null : ConnectorErrors.of(result.error()), null);
    }


    private static Verified listed(ConnectorManifest manifest, Supplier<ConnectorToolListing> list) {
        final ConnectorToolListing listing;
        try {
            listing = list.get();
        } catch (RuntimeException ex) {
            warn(STEP_TOOL_LIST, manifest.id(), ex);
            return new Verified(ConnectorErrors.unavailable(), null);
        }
        if (!listing.ok()) {
            return new Verified(ConnectorErrors.of(listing.error()), null);
        }
        return new Verified(null, new ListedTools(listing.tools()));
    }


    /** 실패한 단계와 커넥터 번호와 예외 종류만 남긴다. 예외 메시지와 원격 응답에는 칸 값이 섞일 수 있어 적지 않는다. */
    static void warn(String step, String connectorId, RuntimeException ex) {
        log.warn("connector {} failed at {}: {}", connectorId, step, ex.getClass().getSimpleName());
    }
}
