package com.bifos.assistant.browser.presentation;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 브라우저 중계의 WebSocket 을 받는다.
 *
 * <p>중계 아래 전체를 이 처리기 매핑이 받는다. {@link BrowserGatewayController} 의 받기 매핑이 WebSocket 으로 올리자는 요청을 받지 않으므로
 * 그 요청은 모두 여기로 온다. devtools 경로가 아니면 {@link BrowserGatewayHandshake} 가 빈 404 로 거절한다.
 *
 * <p>{@code Origin} 은 {@link BrowserGatewayHandshake} 가 직접 보고 403 으로 거절한다. 그래서 Spring 의 Origin 검사는 모두 받게 둔다.
 * 버퍼 크기는 바꾸지 않는다. 조각째 넘기므로 기본 버퍼로 충분하다.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class BrowserGatewaySocketConfig implements WebSocketConfigurer {

    private final BrowserGatewaySocket socket;
    private final BrowserGatewayHandshake handshake;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/internal/browser-gateway/**")
                .addInterceptors(handshake)
                .setAllowedOriginPatterns("*");
    }
}
