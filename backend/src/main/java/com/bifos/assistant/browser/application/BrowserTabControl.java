package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.dto.BrowserEndpoint;
import com.bifos.assistant.browser.application.dto.BrowserTabs;
import com.bifos.assistant.browser.application.dto.TabCloseResult;
import com.bifos.assistant.browser.application.dto.TabRef;
import com.bifos.assistant.browser.application.port.BrowserKeptTabs;
import com.bifos.assistant.browser.application.port.BrowserUsageHandle;
import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpTarget;
import com.bifos.assistant.browser.domain.CdpTargets;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 에이전트가 그 사용자의 「내 브라우저」 에서 탭을 보고 열고 닫는다(docs/features/agent-browser/design.md 의 「PR1 도구」).
 *
 * <p>사용자 번호로만 브라우저를 고른다. 다른 사용자의 탭 번호를 받아도 그 사용자의 브라우저에서 찾지 못해 건너뛴다. 에이전트가 쥔 탭과 넘기기
 * 카드가 지키는 탭은 목록에서 빼고 닫지 않는다. 켜고 끄는 스위치의 판정은 부르는 쪽({@link BrowserAgentControl})이 한다. 예외 글과 결과에
 * 탭 제목과 주소를 넣지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserTabControl {

    /** 점으로 나눈 IPv4 표기다. 이름 풀이 없이 IP 로 읽을 것만 고른다. */
    private static final Pattern IPV4 = Pattern.compile("^[0-9.]+$");

    private final UserBrowserRepository repository;
    private final UserBrowserService browsers;
    private final BrowserRuntime runtime;
    private final CdpTargets targets;
    private final BrowserAgentTabs agentTabs;
    private final BrowserKeptTabs keptTabs;
    private final BrowserHumanHold holds;
    private final BrowserUsage usage;


    /** 켜져 있는 브라우저와 그 CDP 주소다. */
    private record Running(UserBrowser browser, URI cdp) {
    }


    /** 그 주소의 지문이다. 승인 카드와 실행이 같은 값을 쓴다. */
    public static String urlHash(String url) {
        return Sha256.hex(url == null ? "" : url);
    }


    /** 사람이 쓰는 탭 목록이다. 브라우저가 꺼져 있으면 켜지 않고 빈 목록이다. */
    public BrowserTabs list(Long userId) {
        Optional<Running> running = running(userId);
        if (running.isEmpty()) {
            return new BrowserTabs(false, List.of());
        }
        List<CdpTarget> pages = targets.list(running.get().cdp());
        Set<String> blocked = blocked(userId, running.get(), pages);
        return new BrowserTabs(true, pages.stream().filter(page -> !blocked.contains(page.id())).toList());
    }


    /**
     * 그 주소로 새 탭을 연다. 꺼져 있으면 켠다.
     *
     * @return 연 탭 번호
     * @throws IllegalArgumentException 주소가 {@code http}, {@code https} 가 아니다
     * @throws ApiException 사람이 쥐고 있으면 {@code BROWSER_HUMAN_HELD}. 켜지 못하면 {@link UserBrowserService#ensureRunning} 의 오류
     */
    public String open(Long userId, String url) {
        requireWebAddress(url);
        requireNotHeld(userId);
        BrowserEndpoint endpoint = browsers.ensureRunning(userId);
        try (BrowserUsageHandle ignored = usage.open(endpoint.browserId())) {
            String id = targets.create(endpoint.cdp(), url).id();
            browsers.touch(userId);
            return id;
        }
    }


    /**
     * 그 탭들을 닫는다. 지금 없거나, 주소의 지문이 다르거나, 에이전트가 쥐었거나, 넘기기 카드가 지키는 탭은 건너뛴다. 꺼져 있으면 켜지 않고
     * 모두 건너뛴다. 닫다 실패한 탭도 건너뛴 것으로 센다.
     *
     * @throws ApiException 사람이 쥐고 있으면 {@code BROWSER_HUMAN_HELD}
     */
    public TabCloseResult close(Long userId, List<TabRef> refs) {
        requireNotHeld(userId);
        Optional<Running> running = running(userId);
        if (running.isEmpty()) {
            return new TabCloseResult(0, refs.size());
        }
        URI cdp = running.get().cdp();
        try (BrowserUsageHandle ignored = usage.open(running.get().browser().id())) {
            List<CdpTarget> pages = targets.list(cdp);
            Map<String, CdpTarget> byId = pages.stream().collect(Collectors.toMap(CdpTarget::id, Function.identity(), (a, b) -> a));
            Set<String> blocked = blocked(userId, running.get(), pages);
            int closed = 0;
            for (TabRef ref : refs) {
                CdpTarget page = byId.get(ref.id());
                if (page == null || blocked.contains(page.id()) || !urlHash(page.url()).equals(ref.urlHash())) {
                    continue;
                }
                try {
                    targets.close(cdp, page.id());
                    closed++;
                } catch (RuntimeException ex) {
                    log.warn("browser tab close failed kind={}", ex.getClass().getSimpleName());
                }
            }
            browsers.touch(userId);
            return new TabCloseResult(closed, refs.size() - closed);
        }
    }


    private Optional<Running> running(Long userId) {
        return repository.findByUserId(userId)
            .filter(browser -> browser.status() == UserBrowserStatus.RUNNING && browser.containerId() != null)
            .flatMap(browser -> runtime.cdpAddress(browser.containerId()).map(cdp -> new Running(browser, cdp)));
    }


    /** 에이전트가 쥔 탭과 지킬 탭이다. 지킬 탭을 판정하지 못하면 그 탭을 지킨다. */
    private Set<String> blocked(Long userId, Running running, List<CdpTarget> pages) {
        Set<String> blocked = new HashSet<>(agentTabs.agentTabs(running.browser().id(), running.cdp(), pages));
        for (CdpTarget page : pages) {
            if (blocked.contains(page.id())) {
                continue;
            }
            try {
                if (running.browser().startedAt() == null || keptTabs.kept(userId, page.id(), running.browser().startedAt())) {
                    blocked.add(page.id());
                }
            } catch (RuntimeException ex) {
                log.warn("browser kept tab check failed kind={}", ex.getClass().getSimpleName());
                blocked.add(page.id());
            }
        }
        return blocked;
    }


    private void requireNotHeld(Long userId) {
        if (holds.held(userId)) {
            throw new ApiException(ErrorCode.BROWSER_HUMAN_HELD, "user browser is held by a person");
        }
    }


    /**
     * 사람이 보는 웹 주소인지 본다. {@code http}, {@code https} 이고, 브라우저 망 안이나 그 기계를 가리키는 주소(localhost, 점 없는 호스트,
     * loopback·사설·link-local IP)가 아니어야 한다. 이름 풀이는 하지 않는다. 막는 사이트 목록은 다음 단계에서 더한다(design 의 「뒤 PR 의
     * 경계」).
     */
    public static void requireWebAddress(String url) {
        URI uri;
        try {
            uri = url == null ? null : URI.create(url.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("browser tab url is not valid", ex);
        }
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || host.isEmpty() || internalHost(host)) {
            throw new IllegalArgumentException("browser tab url is not a public web address");
        }
    }


    private static boolean internalHost(String host) {
        if (host.startsWith("[") || IPV4.matcher(host).matches()) {
            try {
                InetAddress address = InetAddress.ofLiteral(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
                return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress() || uniqueLocal(address);
            } catch (IllegalArgumentException ex) {
                return true;
            }
        }
        return !host.contains(".") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal");
    }


    /** IPv6 의 사설 범위(fc00::/7)다. {@link InetAddress#isSiteLocalAddress()} 는 옛 fec0::/10 만 본다. */
    private static boolean uniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
