package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.dto.BrowserScreenInput;
import com.bifos.assistant.browser.domain.CdpConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 화면 입력의 CDP 인자만 만든다. 연결과 화면 상태는 세션이 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BrowserScreenInputParams {

    /** 지금 탭의 이력에서 한 칸 뒤로 간다. 결과를 기다리지 않고 실패는 {@code failed} 로 알린다. */
    static void back(CdpConnection current, Runnable failed) {
        current.send("Page.getNavigationHistory", Map.of())
            .thenCompose(history -> {
                int index = history.path("currentIndex").asInt();
                JsonNode entries = history.path("entries");
                if (index < 1 || !entries.has(index - 1)) {
                    return CompletableFuture.completedFuture(null);
                }
                return current.send(
                    "Page.navigateToHistoryEntry",
                    Map.of("entryId", entries.get(index - 1).path("id").asInt()));
            })
            .exceptionally(ex -> {
                failed.run();
                return null;
            });
    }


    /** 비율 좌표를 마지막 프레임의 CSS 픽셀로 바꾼다. */
    static Map<String, Object> point(BrowserScreenInput input, int width, int height) {
        Map<String, Object> params = new HashMap<>();
        params.put("x", input.x() * width);
        params.put("y", input.y() * height);
        return params;
    }


    /** 세션이 정한 버튼 상태를 좌표 인자에 싣는다. */
    static Map<String, Object> mouse(Map<String, Object> params, String action, boolean pressed) {
        String type = switch (action) {
            case "down" -> "mousePressed";
            case "up" -> "mouseReleased";
            default -> "mouseMoved";
        };
        boolean click = !"mouseMoved".equals(type);
        params.put("type", type);
        params.put("button", click || pressed ? "left" : "none");
        params.put("buttons", pressed ? 1 : 0);
        if (click) {
            params.put("clickCount", 1);
        }
        return params;
    }


    /** 키의 누름과 뗌 인자다. 글자를 만드는 Enter 와 Space 는 누름에 text 를 싣는다. */
    static List<Map<String, Object>> key(String name) {
        Integer keyCode = BrowserScreenInput.KEYS.get(name);
        if (keyCode == null) {
            return List.of();
        }
        boolean space = "Space".equals(name);
        Map<String, Object> up = Map.of("type", "keyUp", "key", space ? " " : name, "code", name, "windowsVirtualKeyCode", keyCode);
        Map<String, Object> down = new HashMap<>(up);
        down.put("type", "Enter".equals(name) || space ? "keyDown" : "rawKeyDown");
        if ("Enter".equals(name) || space) {
            down.put("text", space ? " " : "\r");
        }
        return List.of(down, up);
    }
}
