package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpTargets;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 플러그인이 사람에게 넘기며 남긴 탭을 본다. 화면 넘기기의 「다 했어요」 가 이어 가기 전에 그 탭이 아직 있는지 묻고, 같은 플러그인의 다음
 * 호출이 카드를 닫으면 그 탭을 닫는다({@code docs/features/mobile-default-chat/design.md} 의 「단추 닫기 API」, 「플러그인 성공으로 닫기」).
 *
 * <p>브라우저를 켜지 않는다. 다시 켜진 브라우저에는 그때의 탭이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserLeftTabs {

    private final UserBrowserRepository browsers;
    private final BrowserRuntime runtime;
    private final CdpTargets targets;


    /**
     * 그 탭이 없어진 것이 확실한가. 브라우저가 {@code since} 뒤에 다시 켜졌거나 꺼졌거나, 탭 목록에 그 탭이 없으면 참이다.
     *
     * <p>CDP 주소를 모르거나 탭 목록을 읽지 못하면 판정할 수 없어 거짓이다. 잘못 이어 가도 플러그인이 같은 화면에서 다시 넘긴다(설계
     * DECISION5).
     */
    public boolean gone(Long userId, String tab, Instant since) {
        Optional<UserBrowser> found = browsers.findByUserId(userId);
        if (found.isEmpty() || !runningSince(found.get(), since)) {
            return true;
        }
        try {
            Optional<URI> cdp = Optional.ofNullable(found.get().containerId()).flatMap(runtime::cdpAddress);
            return cdp.isPresent() && targets.list(cdp.get()).stream().noneMatch(target -> target.id().equals(tab));
        } catch (RuntimeException ex) {
            log.warn("남긴 탭을 확인하지 못했다 userId={} kind={}", userId, ex.getClass().getSimpleName());
            return false;
        }
    }


    /**
     * 그 탭을 닫는다. 브라우저가 {@code since} 전부터 켜져 있을 때만 닫는다. 플러그인은 다음 호출을 시작할 때 남긴 탭을 닫으려 하지만 카드가 열린 동안
     * 중계가 거절하고 플러그인은 그 탭을 잊는다. 그래서 카드를 닫은 쪽이 대신 닫는다. 실패는 로그만 남긴다. 남은 탭은 사람이 닫는다.
     */
    public void close(Long userId, String tab, Instant since) {
        try {
            Optional<UserBrowser> found = browsers.findByUserId(userId).filter(browser -> runningSince(browser, since));
            Optional<URI> cdp = found.map(UserBrowser::containerId).flatMap(runtime::cdpAddress);
            if (cdp.isPresent()) {
                targets.closeTab(cdp.get(), tab);
            }
        } catch (RuntimeException ex) {
            log.warn("남긴 탭을 닫지 못했다 userId={} kind={}", userId, ex.getClass().getSimpleName());
        }
    }


    private static boolean runningSince(UserBrowser browser, Instant since) {
        return browser.status() == UserBrowserStatus.RUNNING && browser.startedAt() != null && !browser.startedAt().isAfter(since);
    }
}
