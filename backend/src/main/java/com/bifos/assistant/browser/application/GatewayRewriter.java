package com.bifos.assistant.browser.application;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chrome 의 CDP 대상 한 줄을 중계 주소로 바꾼다({@code docs/backend/user-browser.md} 의 「받는 것」).
 *
 * <p>{@code webSocketDebuggerUrl} 은 경로가 {@code /devtools/page/<번호>} 나 {@code /devtools/browser/<번호>} 일 때만 중계 주소로
 * 바꾸고, 아니면 그 칸을 뺀다. 브라우저 대상 번호는 GUID 라 {@code -} 가 든다. 컨테이너 주소가 나가지 않도록 DevTools 화면 주소 두 칸도 뺀다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class GatewayRewriter {

    private static final String WEB_SOCKET = "webSocketDebuggerUrl";
    private static final Pattern DEVTOOLS_PATH = Pattern.compile("^/devtools/(page|browser)/([A-Za-z0-9-]{1,128})$");

    /**
     * 대상 한 줄을 바꾼 사본을 돌려준다. 객체가 아니면 그대로 돌려준다.
     *
     * @param relayBase {@code ws(s)://<중계>/<접근 표식>} 모양의 앞부분
     */
    public static JsonNode rewrite(JsonNode target, String relayBase) {
        if (!(target instanceof ObjectNode original)) {
            return target;
        }
        ObjectNode copy = original.deepCopy();
        copy.remove("devtoolsFrontendUrl");
        copy.remove("devtoolsFrontendUrlCompat");
        JsonNode socket = copy.get(WEB_SOCKET);
        if (socket == null) {
            return copy;
        }
        String path = socket.isString() ? pathOf(socket.asString()) : null;
        Matcher matcher = path == null ? null : DEVTOOLS_PATH.matcher(path);
        if (matcher != null && matcher.matches()) {
            copy.put(WEB_SOCKET, relayBase + "/devtools/" + matcher.group(1) + "/" + matcher.group(2));
        } else {
            copy.remove(WEB_SOCKET);
        }
        return copy;
    }

    /** 주소의 날 경로다. 주소 모양이 아니면 {@code null} 이다. */
    private static String pathOf(String address) {
        try {
            return URI.create(address).getRawPath();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
