package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.domain.CdpEvent;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 화면 세션이 붙은 탭의 확인 창(alert, confirm, prompt, beforeunload)을 {@code dialog} 사건으로 바꾸는 규칙이다(#702).
 *
 * <p>창이 떠 있는 동안 탭은 다른 입력을 받지 않는다. 사람이 고른 답은 화면 입력 {@code dialog} 로 온다. 계약은
 * {@code docs/features/user-browser.md} 의 「로그인 화면」 이 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BrowserScreenDialog {

    /** 확인 창 글의 상한이다. 사이트가 띄운 글이라 로그와 오류에는 싣지 않는다. */
    static final int MAX_MESSAGE = 500;
    /** 창을 거두는 사건 본문이다. */
    static final Map<String, Object> CLOSED = Map.of("open", false);

    private static final String OPENING = "Page.javascriptDialogOpening";
    private static final String CLOSED_EVENT = "Page.javascriptDialogClosed";


    static boolean handles(CdpEvent event) {
        return OPENING.equals(event.method()) || CLOSED_EVENT.equals(event.method());
    }


    static boolean opening(CdpEvent event) {
        return OPENING.equals(event.method());
    }


    /** 화면에 보낼 본문이다. 창이 닫혔으면 {@link #CLOSED} 다. */
    static Map<String, Object> view(CdpEvent event) {
        if (!opening(event)) {
            return CLOSED;
        }
        JsonNode params = event.params();
        String message = params.path("message").asString("");
        return Map.of(
            "open",
            true,
            "type",
            params.path("type").asString("alert"),
            "message",
            message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message);
    }
}
