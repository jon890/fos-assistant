package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.model.BrowserScreenInput;
import com.bifos.assistant.browser.application.model.BrowserScreenInput.Kind;
import com.bifos.assistant.browser.application.model.UserBrowserSnapshot;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 사용자 브라우저 경로의 응답 모양이다. 계약은 {@code docs/features/user-browser.md} 의 「API(사용자 브라우저)」 가 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserBrowserDtos {

    /**
     * 내 브라우저다. 기능이 꺼져 있으면 {@code enabled} 만 싣고, 브라우저가 없으면 {@code exists} 가 거짓이다.
     *
     * @param idleTimeoutSeconds 자동 중지까지의 유휴 시간(초). 화면이 안내 문구에 쓴다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrowserView(
            boolean enabled,
            Boolean exists,
            String status,
            @JsonInclude(JsonInclude.Include.ALWAYS) String lastError,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant startedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastActiveAt,
            Long idleTimeoutSeconds) {

        static BrowserView disabled() {
            return new BrowserView(false, null, null, null, null, null, null);
        }

        static BrowserView missing(Duration idleTimeout) {
            return new BrowserView(true, false, null, null, null, null, idleTimeout.toSeconds());
        }

        static BrowserView of(UserBrowserSnapshot browser, Duration idleTimeout) {
            return new BrowserView(
                    true,
                    true,
                    browser.status().name(),
                    browser.lastError(),
                    browser.startedAt(),
                    browser.lastActiveAt(),
                    idleTimeout.toSeconds());
        }
    }

    /** 관리자 목록의 한 줄이다. 컨테이너 번호와 프로필 키는 싣지 않는다. */
    public record AdminBrowserView(
            Long id,
            Long userId,
            String userName,
            String status,
            String lastError,
            Instant startedAt,
            Instant lastActiveAt) {

        static AdminBrowserView of(UserBrowserSnapshot browser, String userName) {
            return new AdminBrowserView(
                    browser.id(),
                    browser.userId(),
                    userName,
                    browser.status().name(),
                    browser.lastError(),
                    browser.startedAt(),
                    browser.lastActiveAt());
        }
    }

    /** 화면 입력의 본문이다. 칸은 {@code type} 마다 필요한 것만 쓴다. 오류 메시지에는 칸 이름만 싣고 값은 싣지 않는다. */
    public record ScreenInputRequest(
            String type,
            String action,
            Double x,
            Double y,
            String button,
            Integer deltaY,
            String key,
            String text,
            String url,
            String id,
            Integer width,
            Integer height) {

        private static final JsonMapper JSON = JsonMapper.builder().build();
        private static final Pattern TARGET_ID = Pattern.compile("^[A-Za-z0-9]{1,128}$");
        private static final int MAX_TEXT = 500;
        private static final int MAX_WHEEL = 2000;
        /** 본문의 바이트 상한이다. 가장 긴 입력(주소 2048자)도 넉넉히 들어간다. */
        private static final int MAX_BODY = 8 * 1024;

        /** 본문을 읽고 검사한다. 8KB 를 넘거나 JSON 이 아니거나 모양이 틀리면 값을 싣지 않은 {@code VALIDATION_FAILED} 다. */
        static BrowserScreenInput parse(String body) {
            if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY) {
                throw invalid("body");
            }
            ScreenInputRequest request;
            try {
                request = body == null ? null : JSON.readValue(body, ScreenInputRequest.class);
            } catch (RuntimeException ex) {
                throw invalid("body");
            }
            if (request == null || request.type() == null) {
                throw invalid("type");
            }
            return request.toInput();
        }

        private BrowserScreenInput toInput() {
            Kind kind;
            try {
                kind = Kind.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw invalid("type");
            }
            switch (kind) {
                case MOUSE -> {
                    require("action", "down".equals(action) || "up".equals(action) || "move".equals(action));
                    require("button", button == null || "left".equals(button));
                    requireRatio();
                }
                case WHEEL -> {
                    requireRatio();
                    require("deltaY", deltaY != null && Math.abs(deltaY) <= MAX_WHEEL);
                }
                case SCROLL -> require("action", "top".equals(action) || "bottom".equals(action));
                case KEY -> require("key", key != null && BrowserScreenInput.KEYS.containsKey(key));
                case TEXT -> require("text", text != null && !text.isEmpty() && text.length() <= MAX_TEXT);
                case NAVIGATE -> require("url", BrowserScreenInput.webUrl(url));
                case TAB -> require("id", id != null && TARGET_ID.matcher(id).matches());
                case RESIZE -> {
                    require("width", width != null && width >= 320 && width <= 1600);
                    require("height", height != null && height >= 320 && height <= 2000);
                }
                case BACK, RELOAD -> {}
            }
            return new BrowserScreenInput(
                    kind,
                    action,
                    x == null ? 0 : x,
                    y == null ? 0 : y,
                    deltaY == null ? 0 : deltaY,
                    key,
                    text,
                    url,
                    id,
                    width == null ? 0 : width,
                    height == null ? 0 : height);
        }

        private void requireRatio() {
            require("x", x != null && x >= 0 && x <= 1);
            require("y", y != null && y >= 0 && y <= 1);
        }

        private static void require(String field, boolean valid) {
            if (!valid) {
                throw invalid(field);
            }
        }

        private static ApiException invalid(String field) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, "screen input " + field + " is not valid");
        }
    }
}
