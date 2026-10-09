package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserEndpoint;
import com.bifos.assistant.browser.application.model.BrowserGrant;
import com.bifos.assistant.browser.application.model.BrowserUsageHandle;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 브라우저 중계의 판정이다. 접근 표식에서 주인을 찾아 그 브라우저를 켜 둔다. 중계의 HTTP 와 WebSocket 은 이 서비스만 부른다.
 *
 * <p>판정 순서는 {@code docs/features/user-browser.md} 의 「받는 것」 이다. 표식은 열 때마다 표에서 주인을 다시 읽는다. 거절한 까닭은
 * 종류만 로그에 남기고 표식과 서명은 싣지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserGateway {

    private final BrowserGatewayTokens tokens;
    private final BrowserGrantOwners owners;
    private final UserAccessPolicy access;
    private final UserBrowserService browsers;
    private final BrowserUsage usage;

    /**
     * 표식을 확인하고 주인의 브라우저를 켠 뒤 활동을 기록한다.
     *
     * @throws ApiException 중계나 기능이 꺼졌으면 {@code BROWSER_DISABLED}, 표식이 틀렸거나 주인이 없거나 허용 목록에서 꺼졌으면
     *     {@code BROWSER_NOT_FOUND}. 켜기의 실패는 {@link UserBrowserService#ensureRunning} 의 것을 그대로 던진다
     */
    public GatewayTarget open(String token) {
        if (!tokens.enabled() || !browsers.enabled()) {
            throw new ApiException(ErrorCode.BROWSER_DISABLED, "browser gateway is disabled");
        }
        BrowserGrant grant = tokens.verify(token).orElseThrow(() -> reject("malformed"));
        Optional<Long> owner =
                grant.kind() == BrowserGrant.Kind.BINDING ? owners.bindingOwner(grant.id()) : Optional.of(grant.id());
        Long userId = owner.orElseThrow(() -> reject("owner_missing"));
        if (!access.allowed(userId)) {
            throw reject("revoked");
        }
        BrowserEndpoint endpoint = browsers.ensureRunning(userId);
        browsers.touch(userId);
        return new GatewayTarget(userId, endpoint.browserId(), endpoint.cdp());
    }

    /** {@link #open(String)} 을 지난 표식의 WebSocket 중계 주소 앞부분이다. 중계가 꺼졌으면 비어 있다. */
    public Optional<String> relayBase(String token) {
        return tokens.relayBase(token);
    }

    /** 중계 연결이 열려 있는 동안 쥐는 핸들이다. 쥐고 있는 동안 자동 중지하지 않는다. WebSocket 이 쓴다. */
    public BrowserUsageHandle hold(GatewayTarget target) {
        return usage.open(target.browserId());
    }

    /** 중계 연결이 이만큼 아무것도 주고받지 않으면 닫는다. 반쯤 끊긴 연결이 {@link #hold} 의 핸들을 계속 쥐지 않게 한다. */
    public Duration idleTimeout() {
        return browsers.idleTimeout();
    }

    /** 중계로 메시지가 오갔다. 1분에 한 번까지 활동을 기록한다. */
    public void touch(GatewayTarget target) {
        browsers.touch(target.userId());
    }

    private static ApiException reject(String reason) {
        log.info("browser gateway rejected reason={}", reason);
        return new ApiException(ErrorCode.BROWSER_NOT_FOUND, "no user browser");
    }
}
