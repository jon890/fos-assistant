package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.port.BrowserGrantOwners;
import com.bifos.assistant.browser.application.dto.BrowserEndpoint;
import com.bifos.assistant.browser.application.dto.BrowserGrant;
import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.application.dto.GatewayTarget;
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
    private final BrowserAgentTabs agentTabs;
    private final BrowserHumanHold holds;


    /**
     * 표식을 확인하고 주인의 브라우저를 켠 뒤 활동을 기록한다.
     *
     * @throws ApiException 중계나 기능이 꺼졌으면 {@code BROWSER_DISABLED}, 표식이 틀렸거나 주인이 없거나 허용 목록에서 꺼졌으면
     * {@code BROWSER_NOT_FOUND}, 사람이 브라우저를 쥐고 있으면 브라우저를 켜지 않고 {@code BROWSER_HUMAN_HELD}. 켜기의 실패는 {@link UserBrowserService#ensureRunning} 의 것을 그대로 던진다
     */
    public GatewayTarget open(String token) {
        if (!tokens.enabled() || !browsers.enabled()) {
            throw new ApiException(ErrorCode.BROWSER_DISABLED, "browser gateway is disabled");
        }
        BrowserGrant grant = tokens.verify(token).orElseThrow(() -> reject("malformed"));
        Optional<Long> owner = grant.kind() == BrowserGrant.Kind.BINDING ? owners.bindingOwner(grant.id()) : Optional.of(grant.id());
        Long userId = owner.orElseThrow(() -> reject("owner_missing"));
        if (!access.allowed(userId)) {
            throw reject("revoked");
        }
        // 사람이 쥔 동안 에이전트는 브라우저에 아무것도 하지 못한다(ADR-20261116 / browser-lock)
        if (holds.held(userId)) {
            throw new ApiException(ErrorCode.BROWSER_HUMAN_HELD, "user browser is held by a person");
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


    /**
     * 중계 연결이 그 탭에 붙어 있는 동안 에이전트가 그 탭을 쥔다. 브라우저 수준 연결({@code browser})은 탭을 쥐지 않아 아무것도 하지 않는
     * 핸들이다.
     */
    public BrowserUsageHandle holdTab(GatewayTarget target, String kind, String targetId) {
        return "page".equals(kind) ? agentTabs.hold(target.browserId(), targetId) : () -> {
        };
    }


    /**
     * 열린 중계 연결을 사람 잠금에 등록한다. 사람이 넘겨받으면 {@code close} 가 불린다. 등록한 뒤 이미 잠겼으면 바로 부른다. 판정과 등록
     * 사이에 회수가 끼어도 연결이 남지 않게 한다.
     */
    public BrowserUsageHandle watchHuman(GatewayTarget target, Runnable close) {
        BrowserUsageHandle handle = holds.watch(target.browserId(), close);
        if (holds.held(target.userId())) {
            handle.close();
            close.run();
        }
        return handle;
    }


    /** 중계로 그 탭을 만들었다. 연결이 붙기 전에도 에이전트가 쥔 탭으로 둔다. 사람이 쥐고 있으면 두지 않는다. */
    public void created(GatewayTarget target, String targetId) {
        // 판정을 지난 뒤 사람이 넘겨받았으면 그 탭은 사람의 것이다. 회수가 지운 쥔 탭 기록을 다시 적지 않는다
        if (holds.held(target.userId())) {
            return;
        }
        agentTabs.created(target.browserId(), targetId);
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
