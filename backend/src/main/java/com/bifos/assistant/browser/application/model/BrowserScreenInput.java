package com.bifos.assistant.browser.application.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * 화면에 보내는 입력 하나다. 모양은 {@code docs/backend/user-browser.md} 의 「로그인 화면」 이 정하고, 받는 쪽이 이미 검사했다.
 *
 * <p>좌표는 프레임 그림 안의 비율(0~1)이다. 본문(글자, 좌표, 주소)은 로그와 오류 응답에 싣지 않는다.
 *
 * @param kind 입력 종류
 * @param action {@code mouse} 의 {@code down}, {@code up}, {@code move}
 * @param x 가로 비율
 * @param y 세로 비율
 * @param deltaY {@code wheel} 의 세로 움직임
 * @param key {@code key} 의 키 이름. {@link #KEYS} 가운데 하나다
 * @param text {@code text} 의 글자
 * @param url {@code navigate} 의 주소
 * @param targetId {@code tab} 의 탭 번호
 * @param width {@code resize} 의 폭
 * @param height {@code resize} 의 높이
 */
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

    /** 받는 특수 키다. 글자는 {@code text} 로 넣는다. */
    public static final Set<String> KEYS =
            Set.of("Enter", "Backspace", "Tab", "Escape", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Delete");

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
