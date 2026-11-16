package com.bifos.assistant.browser.application;

import static org.mockito.Mockito.mock;
import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpProbe;
import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 다른 패키지의 facade 검사도 운영 기록기와 같은 저장소와 시계를 쓰게 만든다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserBrowserServiceTestFactory {

    public static UserBrowserService create(
        UserBrowserRepository browsers,
        BrowserRuntime runtime,
        BrowserProfileStore profiles,
        CdpProbe cdp,
        LiveProperties<BrowserProperties> properties,
        Clock clock,
        BrowserScreens screens) {
        return create(browsers, runtime, profiles, cdp, properties, clock, screens, mock(BrowserHumanHold.class));
    }


    /** 끄기가 사람 잠금을 비우는 것을 볼 때 실제 잠금을 넘긴다. */
    public static UserBrowserService create(
        UserBrowserRepository browsers,
        BrowserRuntime runtime,
        BrowserProfileStore profiles,
        CdpProbe cdp,
        LiveProperties<BrowserProperties> properties,
        Clock clock,
        BrowserScreens screens,
        BrowserHumanHold holds) {
        return new UserBrowserService(
            browsers, runtime, profiles, cdp, properties, clock, screens, new BrowserActivityTracker(browsers, clock), holds);
    }
}
