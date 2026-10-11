package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.browser.domain.BrowserRuntime;
import com.bifos.assistant.browser.domain.CdpProbe;
import com.bifos.assistant.browser.infra.BrowserProperties;
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
        return new UserBrowserService(
            browsers, runtime, profiles, cdp, properties, clock, screens, new BrowserActivityTracker(browsers, clock));
    }
}
