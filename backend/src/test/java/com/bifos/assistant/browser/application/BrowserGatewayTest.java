package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.model.BrowserEndpoint;
import com.bifos.assistant.browser.application.model.BrowserUsageHandle;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 중계의 판정 순서를 본다. 규칙은 {@code docs/backend/user-browser.md} 의 「받는 것」 이다. 브라우저 서비스는 대역이다. */
class BrowserGatewayTest {

    private static final String BASE = "http://control-plane.example.test/internal/browser-gateway";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final URI CDP = URI.create("http://192.0.2.10:9999");
    private static final long BINDING_ID = 42L;
    private static final Long OWNER = 7L;
    private static final Long BROWSER_ID = 11L;

    private final Set<Long> revoked = new HashSet<>();
    private final Map<Long, Long> bindingOwners = Map.of(BINDING_ID, OWNER);
    private UserBrowserService browsers;
    private BrowserUsage usage;

    @BeforeEach
    void setUp() {
        revoked.clear();
        browsers = mock(UserBrowserService.class);
        when(browsers.enabled()).thenReturn(true);
        when(browsers.ensureRunning(OWNER)).thenReturn(new BrowserEndpoint(BROWSER_ID, CDP));
        usage = new BrowserUsage();
    }

    @Test
    @DisplayName("바인딩 표식은 그 연결 주인의 브라우저를 켜고 활동을 기록한 뒤 넘길 곳을 준다")
    void opensOwnersBrowserForBindingToken() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);
        BrowserGateway gateway = gateway(tokens);

        GatewayTarget target =
                gateway.open(token(tokens.bindingAddress(BINDING_ID).orElseThrow()));

        assertThat(target).isEqualTo(new GatewayTarget(OWNER, BROWSER_ID, CDP));
        verify(browsers).ensureRunning(OWNER);
        verify(browsers).touch(OWNER);
    }

    @Test
    @DisplayName("호출 표식은 그 사용자의 브라우저를 켜고 쥔 핸들은 닫을 때까지 사용 중으로 센다")
    void opensUsersBrowserForCallTokenAndHoldsIt() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);
        BrowserGateway gateway = gateway(tokens);

        GatewayTarget target = gateway.open(token(tokens.callAddress(OWNER).orElseThrow()));
        BrowserUsageHandle handle = gateway.hold(target);

        assertThat(target).isEqualTo(new GatewayTarget(OWNER, BROWSER_ID, CDP));
        assertThat(usage.inUse(BROWSER_ID)).isTrue();
        handle.close();
        assertThat(usage.inUse(BROWSER_ID)).isFalse();
    }

    @Test
    @DisplayName("주인이 허용 목록에서 꺼졌으면 BROWSER_NOT_FOUND 이고 브라우저를 켜지 않는다")
    void rejectsRevokedOwner() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);
        revoked.add(OWNER);

        assertCode(
                () -> gateway(tokens)
                        .open(token(tokens.bindingAddress(BINDING_ID).orElseThrow())),
                ErrorCode.BROWSER_NOT_FOUND);
        verify(browsers, never()).ensureRunning(anyLong());
    }

    @Test
    @DisplayName("바인딩이 없으면 서명이 맞아도 BROWSER_NOT_FOUND 이고 브라우저를 켜지 않는다")
    void rejectsMissingBinding() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);

        assertCode(
                () -> gateway(tokens)
                        .open(token(tokens.bindingAddress(BINDING_ID + 1).orElseThrow())),
                ErrorCode.BROWSER_NOT_FOUND);
        verify(browsers, never()).ensureRunning(anyLong());
    }

    @Test
    @DisplayName("모양이 틀린 표식은 BROWSER_NOT_FOUND 다")
    void rejectsMalformedToken() {
        assertCode(() -> gateway(tokens(BASE, SECRET)).open("b42.nope"), ErrorCode.BROWSER_NOT_FOUND);
    }

    @Test
    @DisplayName("중계 설정이 비었거나 기능이 꺼졌으면 BROWSER_DISABLED 다")
    void rejectsWhenGatewayOrFeatureIsOff() {
        String token = token(tokens(BASE, SECRET).bindingAddress(BINDING_ID).orElseThrow());

        assertCode(() -> gateway(tokens(BASE, null)).open(token), ErrorCode.BROWSER_DISABLED);

        when(browsers.enabled()).thenReturn(false);
        assertCode(() -> gateway(tokens(BASE, SECRET)).open(token), ErrorCode.BROWSER_DISABLED);
        verify(browsers, never()).ensureRunning(anyLong());
    }

    @Test
    @DisplayName("브라우저를 켜다 난 오류는 그대로 던진다")
    void propagatesStartFailure() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);
        when(browsers.ensureRunning(OWNER))
                .thenThrow(new ApiException(ErrorCode.BROWSER_CAPACITY, "too many browsers are running"));

        assertCode(
                () -> gateway(tokens)
                        .open(token(tokens.bindingAddress(BINDING_ID).orElseThrow())),
                ErrorCode.BROWSER_CAPACITY);
        verify(browsers, never()).touch(OWNER);
    }

    private BrowserGateway gateway(BrowserGatewayTokens tokens) {
        BrowserGrantOwners owners = bindingId -> Optional.ofNullable(bindingOwners.get(bindingId));
        return new BrowserGateway(tokens, owners, userId -> !revoked.contains(userId), browsers, usage);
    }

    private static BrowserGatewayTokens tokens(String baseUrl, String secret) {
        BrowserProperties properties = new BrowserProperties(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1024,
                null,
                null,
                null,
                2,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                baseUrl,
                secret);
        return new BrowserGatewayTokens(
                LiveProperties.fixed(BrowserProperties.class, properties),
                Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC));
    }

    private static String token(String address) {
        return address.substring(BASE.length() + 1);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
