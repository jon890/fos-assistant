package com.bifos.assistant.browser.application;

import java.util.List;
import java.util.ArrayList;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.application.dto.UserBrowserSnapshot;

import com.bifos.assistant.browser.application.port.BrowserGrantOwners;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.dto.BrowserEndpoint;
import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.application.dto.GatewayTarget;
import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.testsupport.InMemoryServerSecrets;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 중계의 판정 순서를 본다. 규칙은 {@code docs/features/user-browser.md} 의 「받는 것」 이다. 브라우저 서비스는 대역이다. */
class BrowserGatewayTest {

    private static final String BASE = "http://control-plane.example.test/internal/browser-gateway";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final URI CDP = URI.create("http://192.0.2.10:9999");
    private static final long BINDING_ID = 42L;
    private static final Long OWNER = 7L;
    private static final Long BROWSER_ID = 11L;
    private static final long OTHER_BINDING_ID = 77L;
    private static final Long OTHER_OWNER = 8L;
    private static final Long OTHER_BROWSER_ID = 12L;
    private static final URI OTHER_CDP = URI.create("http://192.0.2.20:9999");

    private final Set<Long> revoked = new HashSet<>();
    private final Map<Long, Long> bindingOwners = Map.of(BINDING_ID, OWNER, OTHER_BINDING_ID, OTHER_OWNER);
    private UserBrowserService browsers;
    private BrowserUsage usage;
    private final BrowserHumanHold holds = mock(BrowserHumanHold.class);
    private final BrowserAgentTabs agentTabs = new BrowserAgentTabs(new FakeCdp(), Clock.systemUTC());
    private final Set<String> keptTabs = new HashSet<>();
    private final List<Instant> keptSince = new ArrayList<>();


    @BeforeEach
    void setUp() {
        revoked.clear();
        browsers = mock(UserBrowserService.class);
        when(browsers.enabled()).thenReturn(true);
        when(browsers.ensureRunning(OWNER)).thenReturn(new BrowserEndpoint(BROWSER_ID, CDP));
        when(browsers.ensureRunning(OTHER_OWNER)).thenReturn(new BrowserEndpoint(OTHER_BROWSER_ID, OTHER_CDP));
        usage = new BrowserUsage();
    }


    @Test
    @DisplayName("바인딩 표식은 그 연결 주인의 브라우저를 켜고 활동을 기록한 뒤 넘길 곳을 준다")
    void opensOwnersBrowserForBindingToken() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);
        BrowserGateway gateway = gateway(tokens);

        GatewayTarget target = gateway.open(token(tokens.bindingAddress(BINDING_ID).orElseThrow()));

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
    @DisplayName("합성 사용자 둘의 표식은 각자 자기 브라우저만 켜고, 표식의 번호를 남의 것으로 바꾸면 남의 브라우저를 켜지 않는다")
    void twoUsersReachOnlyTheirOwnBrowsers() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);
        BrowserGateway gateway = gateway(tokens);
        String ownBinding = token(tokens.bindingAddress(BINDING_ID).orElseThrow());
        String ownCall = token(tokens.callAddress(OWNER).orElseThrow());

        assertThat(gateway.open(ownBinding)).isEqualTo(new GatewayTarget(OWNER, BROWSER_ID, CDP));
        assertThat(gateway.open(ownCall)).isEqualTo(new GatewayTarget(OWNER, BROWSER_ID, CDP));
        assertThat(gateway.open(token(tokens.bindingAddress(OTHER_BINDING_ID).orElseThrow())))
            .isEqualTo(new GatewayTarget(OTHER_OWNER, OTHER_BROWSER_ID, OTHER_CDP));
        assertThat(gateway.open(token(tokens.callAddress(OTHER_OWNER).orElseThrow())))
            .isEqualTo(new GatewayTarget(OTHER_OWNER, OTHER_BROWSER_ID, OTHER_CDP));

        // 자기 서명을 그대로 두고 번호만 다른 사용자의 바인딩과 사용자로 바꾼다.
        String forgedBinding = ownBinding.replaceFirst("^b" + BINDING_ID + "\\.", "b" + OTHER_BINDING_ID + ".");
        String forgedCall = ownCall.replaceFirst("^u" + OWNER + "\\.", "u" + OTHER_OWNER + ".");
        assertThat(forgedBinding).isNotEqualTo(ownBinding);
        assertThat(forgedCall).isNotEqualTo(ownCall);
        assertCode(() -> gateway.open(forgedBinding), ErrorCode.BROWSER_NOT_FOUND);
        assertCode(() -> gateway.open(forgedCall), ErrorCode.BROWSER_NOT_FOUND);

        verify(browsers, times(2)).ensureRunning(OWNER);
        verify(browsers, times(2)).ensureRunning(OTHER_OWNER);
    }


    @Test
    @DisplayName("사람이 브라우저를 쥐고 있으면 BROWSER_HUMAN_HELD 이고 브라우저를 켜지 않는다")
    void rejectsWhileHeldByPerson() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET);
        when(holds.held(OWNER)).thenReturn(true);

        assertCode(
            () -> gateway(tokens).open(token(tokens.callAddress(OWNER).orElseThrow())),
            ErrorCode.BROWSER_HUMAN_HELD);
        verify(browsers, never()).ensureRunning(anyLong());
    }


    @Test
    @DisplayName("판정 뒤 사람이 넘겨받았으면 중계로 만든 탭을 에이전트가 쥔 탭으로 두지 않는다")
    void createdIsIgnoredWhileHeld() {
        BrowserGateway gateway = gateway(tokens(BASE, SECRET));
        GatewayTarget target = new GatewayTarget(OWNER, BROWSER_ID, CDP);
        when(holds.held(OWNER)).thenReturn(true);

        gateway.created(target, "T1");

        assertThat(agentTabs.held(BROWSER_ID, "T1")).isFalse();
    }


    @Test
    @DisplayName("등록한 뒤 이미 사람이 쥐었으면 그 연결을 바로 닫고 등록을 뺀다")
    void watchClosesAtOnceWhenAlreadyHeld() {
        BrowserGateway gateway = gateway(tokens(BASE, SECRET));
        GatewayTarget target = new GatewayTarget(OWNER, BROWSER_ID, CDP);
        BrowserUsageHandle handle = mock(BrowserUsageHandle.class);
        when(holds.watch(eq(BROWSER_ID), any())).thenReturn(handle);
        when(holds.held(OWNER)).thenReturn(true);
        AtomicInteger closed = new AtomicInteger();

        gateway.watchHuman(target, closed::incrementAndGet);

        assertThat(closed.get()).isEqualTo(1);
        verify(handle).close();
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


    @Test
    @DisplayName("탭 연결(page)은 열린 동안 그 탭을 쥐고, 브라우저 수준 연결은 쥐지 않으며, 중계로 만든 탭은 바로 쥔다")
    void holdsOnlyPageConnectionsAndCreatedTabs() {
        BrowserGateway gateway = gateway(tokens(BASE, SECRET));
        GatewayTarget target = new GatewayTarget(OWNER, BROWSER_ID, CDP);

        BrowserUsageHandle page = gateway.holdTab(target, "page", "PAGE1");
        BrowserUsageHandle browser = gateway.holdTab(target, "browser", "PAGE2");
        gateway.created(target, "NEW1");

        assertThat(agentTabs.held(BROWSER_ID, "PAGE1")).isTrue();
        assertThat(agentTabs.held(BROWSER_ID, "PAGE2")).isFalse();
        assertThat(agentTabs.held(BROWSER_ID, "NEW1")).isTrue();
        page.close();
        browser.close();
        assertThat(agentTabs.held(BROWSER_ID, "PAGE1")).isFalse();
    }


    @Test
    @DisplayName("열린 넘기기나 이어 가기 표식의 탭은 이번 켜기 시각으로 물어 닫지 않고, 판정하지 못하면 닫지 않는 쪽이다")
    void keepsTabsOfOpenHandoffsAndWaitingApprovals() {
        BrowserGateway gateway = gateway(tokens(BASE, SECRET));
        Instant started = Instant.parse("2026-11-17T00:00:00Z");
        when(browsers.get(OWNER)).thenReturn(Optional.of(new UserBrowserSnapshot(BROWSER_ID, OWNER,
            UserBrowserStatus.RUNNING, null, started, started)));
        GatewayTarget target = new GatewayTarget(OWNER, BROWSER_ID, CDP);
        keptTabs.add("KEPT1");

        assertThat(gateway.keeps(target, "KEPT1")).isTrue();
        assertThat(gateway.keeps(target, "OTHER1")).isFalse();
        assertThat(gateway.keeps(target, "FAIL")).isTrue();
        assertThat(keptSince).containsOnly(started);
    }


    private BrowserGateway gateway(BrowserGatewayTokens tokens) {
        BrowserGrantOwners owners = bindingId -> Optional.ofNullable(bindingOwners.get(bindingId));
        return new BrowserGateway(tokens, owners, userId -> !revoked.contains(userId), browsers, usage, agentTabs, holds,
            (userId, tab, since) -> {
                keptSince.add(since);
                if ("FAIL".equals(tab)) {
                    throw new IllegalStateException("db");
                }
                return keptTabs.contains(tab);
            });
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
            Duration.ofMinutes(30),
            baseUrl,
            null);
        return new BrowserGatewayTokens(
            LiveProperties.fixed(BrowserProperties.class, properties),
            InMemoryServerSecrets.with(BrowserGatewayTokens.SECRET_NAME, secret),
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
