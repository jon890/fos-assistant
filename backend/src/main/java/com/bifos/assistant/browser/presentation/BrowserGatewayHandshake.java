package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.BrowserGateway;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import com.bifos.assistant.shared.error.ApiException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 브라우저 중계의 WebSocket handshake 를 판정한다. 계약은 {@code docs/features/user-browser.md} 의 「받는 것」 이다.
 *
 * <p>중계 아래의 WebSocket 요청은 모두 여기로 온다. 경로가 {@code /internal/browser-gateway/<표식>/devtools/<종류>/<번호>} 모양이
 * 아니면 빈 404 로 거절한다. {@code GET} 이 아니거나 {@code Upgrade} 가 {@code websocket} 이 아니면 브라우저를 켜지 않고 빈 400 으로
 * 거절한다. 판정의 오류는 {@link BrowserGatewayStatus} 의 표로 상태를 정한다. 거절한 응답의 본문은 비운다.
 *
 * <p>이 판정은 예외를 던지지 않는다. 던지면 Spring 이 요청 주소를 실은 예외로 감싸 전역 오류 처리기로 보내고, 그 주소에는 접근 표식이
 * 들어 있다. 같은 까닭으로 로그에는 오류 코드나 예외 종류만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserGatewayHandshake implements HandshakeInterceptor {

    /** 판정을 지난 {@link GatewayTarget} 을 두는 세션 속성이다. */
    static final String TARGET = BrowserGatewayHandshake.class.getName() + ".target";
    /** Chrome 쪽 대상의 종류({@code page}, {@code browser})를 두는 세션 속성이다. */
    static final String KIND = BrowserGatewayHandshake.class.getName() + ".kind";
    /** Chrome 쪽 대상의 번호를 두는 세션 속성이다. */
    static final String TARGET_ID = BrowserGatewayHandshake.class.getName() + ".targetId";

    /** 경로는 풀지 않은 원문으로 본다. 표식은 {@code /} 를 담지 않는다. */
    private static final Pattern PATH =
            Pattern.compile("^/internal/browser-gateway/([^/]+)/devtools/(page|browser)/([A-Za-z0-9-]{1,128})$");

    private final BrowserGateway gateway;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        // 브라우저 페이지가 이 경로에 붙지 못하게 한다. 커넥터는 Origin 을 보내지 않는다
        if (request.getHeaders().getOrigin() != null) {
            return refuse(response, HttpStatus.FORBIDDEN);
        }
        Matcher path = PATH.matcher(request.getURI().getRawPath());
        if (!path.matches()) {
            return refuse(response, HttpStatus.NOT_FOUND);
        }
        // 브라우저를 켜기 전에 handshake 모양을 본다. 모양 검사는 이 판정 뒤에 Spring 이 하므로, 그 전에 켜지 않게 한다
        if (!isUpgrade(request)) {
            return refuse(response, HttpStatus.BAD_REQUEST);
        }
        GatewayTarget target;
        try {
            target = gateway.open(path.group(1));
        } catch (ApiException ex) {
            log.warn("browser gateway socket refused code={}", ex.code());
            return refuse(response, BrowserGatewayStatus.of(ex.code()));
        } catch (RuntimeException ex) {
            log.warn("browser gateway socket failed kind={}", ex.getClass().getSimpleName());
            return refuse(response, HttpStatus.BAD_GATEWAY);
        }
        attributes.put(TARGET, target);
        attributes.put(KIND, path.group(2));
        attributes.put(TARGET_ID, path.group(3));
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
        // 열린 뒤의 일은 처리기가 맡는다
    }

    private static boolean isUpgrade(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod())
                && "websocket".equalsIgnoreCase(request.getHeaders().getUpgrade());
    }

    private static boolean refuse(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }
}
