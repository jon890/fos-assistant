package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.dao.OptimisticLockingFailureException;

/** ケ�진 브라우저의 활동 시각 저장과 사용자별 1분 제한 상태를 함께 갖는다. */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "com.bifos.assistant.browser.application.UserBrowserService")
final class BrowserActivityTracker {

    // 활동을 다시 기록하기까지의 간격이다. UserBrowser.touch 와 같다
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final UserBrowserRepository browsers;
    private final Clock clock;
    /** 사용자마다 마지막으로 활동을 기록한 시각이다. 간격 안의 입력은 DB 를 읽지 않고 거른다. */
    private final Map<Long, Instant> touched = new ConcurrentHashMap<>();

    void touch(Long userId) {
        Instant now = clock.instant();
        Instant last = touched.get(userId);
        if (last != null && !now.isBefore(last) && now.isBefore(last.plus(TOUCH_INTERVAL))) {
            return;
        }
        browsers.findByUserId(userId)
                .filter(browser -> browser.status() == UserBrowserStatus.RUNNING)
                .ifPresent(browser -> {
                    if (!browser.touch(now)) {
                        touched.put(userId, browser.lastActiveAt());
                        return;
                    }
                    try {
                        browsers.save(browser);
                        touched.put(userId, now);
                    } catch (OptimisticLockingFailureException ex) {
                        log.debug("user browser touch skipped id={}", browser.id());
                    }
                });
    }

    void clear(Long userId) {
        touched.remove(userId);
    }
}
