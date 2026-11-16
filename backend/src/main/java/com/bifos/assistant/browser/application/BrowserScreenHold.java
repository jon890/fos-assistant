package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.port.BrowserScreenSink;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 화면 하나가 마지막으로 보낸 잠금 상태다. {@code hold} 사건을 바뀌었을 때만 보내게 한다. 뜻은 {@code docs/features/browser-cowork/design.md}
 * 의 「PR3 사람 잠금」 이다.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class BrowserScreenHold {

    private final BrowserHumanHold holds;
    private final Long userId;
    private Map<String, Object> last;


    /**
     * 지금 잠금 상태가 앞서 보낸 것과 다르면 {@code hold} 사건으로 보낸다.
     *
     * @param agentTabs 지금 탭들에 에이전트가 쥔 탭이 있는가. 없으면 승인한 실행을 묻지 않는다
     * @return 받는 쪽이 끊겼으면 거짓. 보낼 것이 없었으면 참이다
     */
    synchronized boolean send(BrowserScreenSink sink, boolean agentTabs) {
        Map<String, Object> view;
        try {
            view = holds.view(userId, agentTabs);
        } catch (RuntimeException ex) {
            // 잠금을 읽지 못해도 화면은 잇는다. 다음 탭 확인에서 다시 본다
            log.debug("browser screen hold read failed error={}", ex.getClass().getSimpleName());
            return true;
        }
        if (Objects.equals(view, last)) {
            return true;
        }
        last = view;
        return sink.send("hold", view);
    }
}
