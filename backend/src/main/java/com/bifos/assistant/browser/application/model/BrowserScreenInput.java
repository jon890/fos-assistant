package com.bifos.assistant.browser.application.model;

import static java.util.Map.entry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/** 화면에 보내는 입력 하나다. 모양은 {@code docs/backend/user-browser.md} 의 「로그인 화면」 이 정하고, 받는 쪽이 이미 검사했다. */
public record BrowserScreenInput(
        Kind kind,
        String action,
        double x,
        double y,
        int deltaY,
        String key,
        String text,
        String url,
        String targetId,
        int width,
        int height) {

    /** 받는 특수 키와 그 CDP {@code windowsVirtualKeyCode} 다. 글자는 {@code text} 로 넣는다. */
    public static final Map<String, Integer> KEYS = Map.ofEntries(
            entry("Enter", 13),
            entry("Backspace", 8),
            entry("Tab", 9),
            entry("Escape", 27),
            entry("ArrowLeft", 37),
            entry("ArrowUp", 38),
            entry("ArrowRight", 39),
            entry("ArrowDown", 40),
            entry("Delete", 46));

    /** 주소의 길이 상한이다. */
    private static final int MAX_URL = 2048;

    /** 입력 종류다. */
    public enum Kind {
        MOUSE,
        WHEEL,
        KEY,
        TEXT,
        NAVIGATE,
        BACK,
        RELOAD,
        TAB,
        RESIZE
    }

    /** 화면이 열 수 있는 주소인가. {@code http} 와 {@code https} 이고 host 가 있어야 한다. */
    public static boolean webUrl(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_URL) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
