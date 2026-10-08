package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.BrowserGateway;
import com.bifos.assistant.browser.application.GatewayRewriter;
import com.bifos.assistant.browser.application.model.GatewayTarget;
import com.bifos.assistant.browser.domain.CdpGatewayHttp;
import com.bifos.assistant.browser.domain.CdpReply;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * 브라우저 중계의 HTTP 창구다. 커넥터가 Chrome 의 CDP 주소처럼 부르는 {@code /internal/browser-gateway/<접근 표식>/} 아래를 받는다.
 *
 * <p>계약은 {@code docs/backend/user-browser.md} 의 「받는 것」 이다. 사용자 JWT 는 보지 않고 접근 표식이 인증이다. 판정은
 * {@link BrowserGateway} 가 한다. 오류 응답의 본문은 비운다. 커넥터는 상태 코드만 본다.
 *
 * <p>오류는 이 컨트롤러 안에서 상태 코드로 바꾸고 전역 오류 처리기로 보내지 않는다. 전역 처리기는 요청 경로를 로그에 남기는데, 이 경로에는
 * 접근 표식이 들어 있다. 같은 까닭으로 로그에는 오류 코드나 예외 종류만 남긴다.
 *
 * <p>WebSocket 으로 올리자는 요청({@code Upgrade: websocket})은 어느 경로든 받지 않는다. 이 처리기 매핑이 WebSocket 처리기 매핑보다
 * 먼저 보므로, 받으면 WebSocket handshake 를 여기서 가로챈다. 그 요청은 모두 {@link BrowserGatewaySocketConfig} 의 매핑이 받는다.
 * {@code Upgrade} 머리 전체를 빼지는 않는다. JDK {@code HttpClient} 는 평범한 요청에도 {@code Upgrade: h2c} 를 실으므로, 그 요청이
 * 이 매핑을 지나치면 전역 오류 처리기가 표식이 든 경로를 로그에 남긴다.
 */
@Slf4j
@RestController
@RequestMapping(
        value = "/internal/browser-gateway/{token}",
        headers = {"Upgrade!=websocket", "Upgrade!=WebSocket"})
@RequiredArgsConstructor
public class BrowserGatewayController {

    /** 탭을 닫고 앞으로 가져오는 경로의 대상 번호다. 경로에 그대로 넣으므로 이것만 받는다. */
    private static final Pattern TAB_ID = Pattern.compile("^[A-Za-z0-9]+$");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int OK = 200;
    private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";
    private static final String SEC_FETCH_MODE = "Sec-Fetch-Mode";

    private final BrowserGateway gateway;
    private final CdpGatewayHttp cdp;

    /** Chrome 의 버전 정보다. 브라우저 대상의 WebSocket 주소를 중계 주소로 바꾼다. */
    @GetMapping("/json/version")
    public ResponseEntity<byte[]> version(@PathVariable String token, HttpServletRequest request) {
        if (fromPage(request)) {
            return empty(HttpStatus.FORBIDDEN);
        }
        return forward(token, target -> rewritten(cdp.get(target.cdp(), "/json/version"), token));
    }

    /** 대상 목록이다. 줄마다 WebSocket 주소를 중계 주소로 바꾼다. */
    @GetMapping({"/json", "/json/list"})
    public ResponseEntity<byte[]> list(@PathVariable String token, HttpServletRequest request) {
        if (fromPage(request)) {
            return empty(HttpStatus.FORBIDDEN);
        }
        return forward(token, target -> rewritten(cdp.get(target.cdp(), "/json/list"), token));
    }

    /**
     * 새 탭을 연다. 쿼리를 푼 주소가 {@code http}, {@code https}, {@code about:blank} 일 때만 넘기고, 넘길 때는 받은 원문 쿼리를 그대로
     * 붙인다. 아니면 400 이다.
     */
    @PutMapping("/json/new")
    public ResponseEntity<byte[]> create(@PathVariable String token, HttpServletRequest request) {
        if (fromPage(request)) {
            return empty(HttpStatus.FORBIDDEN);
        }
        String query = request.getQueryString();
        if (!allowedAddress(query)) {
            return empty(HttpStatus.BAD_REQUEST);
        }
        return forward(token, target -> rewritten(cdp.put(target.cdp(), "/json/new?" + query), token));
    }

    /** 탭을 닫는다. 번호가 영문자와 숫자가 아니면 404 다. Chrome 의 글을 그대로 준다. */
    @GetMapping("/json/close/{id}")
    public ResponseEntity<byte[]> close(
            @PathVariable String token, @PathVariable String id, HttpServletRequest request) {
        return tabCommand(token, "/json/close/", id, request);
    }

    /** 탭을 앞으로 가져온다. 번호가 영문자와 숫자가 아니면 404 다. Chrome 의 글을 그대로 준다. */
    @GetMapping("/json/activate/{id}")
    public ResponseEntity<byte[]> activate(
            @PathVariable String token, @PathVariable String id, HttpServletRequest request) {
        return tabCommand(token, "/json/activate/", id, request);
    }

    /**
     * 그 밖의 경로는 모든 메서드에 빈 404 다.
     *
     * <p>WebSocket 으로 올리자는 요청({@code Upgrade: websocket})은 클래스의 머리 조건이 뺀다. 이 처리기 매핑이 WebSocket 처리기
     * 매핑보다 먼저 보므로, 받으면 WebSocket handshake 를 여기서 가로챈다. {@code Upgrade} 머리 전체를 빼지는 않는다. JDK
     * {@code HttpClient} 는 평범한 요청에도 {@code Upgrade: h2c} 를 실으므로, 그 요청이 이 매핑을 지나치면 전역 오류 처리기가 표식이 든
     * 경로를 로그에 남긴다.
     *
     * <p>머리 조건은 값의 대소문자를 구분해 비교하므로 {@code websocket} 과 {@code WebSocket} 만 뺀다. JDK 와 Bun 의 WebSocket
     * 클라이언트는 {@code websocket} 으로 보낸다.
     */
    @RequestMapping("/**")
    public ResponseEntity<byte[]> unknown(HttpServletRequest request) {
        return empty(fromPage(request) ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<byte[]> tabCommand(String token, String path, String id, HttpServletRequest request) {
        if (fromPage(request)) {
            return empty(HttpStatus.FORBIDDEN);
        }
        if (!TAB_ID.matcher(id).matches()) {
            return empty(HttpStatus.NOT_FOUND);
        }
        return forward(token, target -> passThrough(cdp.get(target.cdp(), path + id)));
    }

    /**
     * 표식을 확인하고 주인의 브라우저에 넘긴다. 실패는 빈 본문의 상태 코드로 바꾼다.
     *
     * <p>Chrome 이 닿지 않는 것 같은 그 밖의 런타임 예외는 502 다.
     */
    private ResponseEntity<byte[]> forward(String token, Function<GatewayTarget, ResponseEntity<byte[]>> call) {
        try {
            GatewayTarget target = gateway.open(token);
            ResponseEntity<byte[]> response = call.apply(target);
            touch(target);
            return response;
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.BROWSER_NOT_FOUND) {
                // 거절한 까닭은 BrowserGateway 가 한 줄 남겼다
                log.debug("browser gateway refused code={}", ex.code());
            } else {
                log.warn("browser gateway refused code={}", ex.code());
            }
            return empty(BrowserGatewayStatus.of(ex.code()));
        } catch (RuntimeException ex) {
            log.warn("browser gateway failed kind={}", ex.getClass().getSimpleName());
            return empty(HttpStatus.BAD_GATEWAY);
        }
    }

    /** 활동을 기록한다. 기록하지 못해도 Chrome 의 답은 이미 받았으므로 응답을 바꾸지 않는다. */
    private void touch(GatewayTarget target) {
        try {
            gateway.touch(target);
        } catch (RuntimeException ex) {
            log.warn("browser gateway touch failed kind={}", ex.getClass().getSimpleName());
        }
    }

    /** Chrome 의 JSON 을 중계 주소로 바꿔 준다. 목록이면 줄마다 바꾼다. Chrome 이 200 이 아니면 502 다. */
    private ResponseEntity<byte[]> rewritten(CdpReply reply, String token) {
        if (reply.status() != OK) {
            log.warn("browser gateway upstream status={}", reply.status());
            return empty(HttpStatus.BAD_GATEWAY);
        }
        String relayBase = gateway.relayBase(token)
                .orElseThrow(() -> new ApiException(ErrorCode.BROWSER_DISABLED, "browser gateway is disabled"));
        JsonNode body = JSON.readTree(reply.body());
        JsonNode result;
        if (body.isArray()) {
            ArrayNode rows = JSON.createArrayNode();
            for (JsonNode row : body) {
                rows.add(GatewayRewriter.rewrite(row, relayBase));
            }
            result = rows;
        } else {
            result = GatewayRewriter.rewrite(body, relayBase);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsBytes(result));
    }

    private static ResponseEntity<byte[]> passThrough(CdpReply reply) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(reply.status());
        if (reply.contentType() != null) {
            builder.header(HttpHeaders.CONTENT_TYPE, reply.contentType());
        }
        return builder.body(reply.body());
    }

    /** 쿼리를 푼 주소가 {@code http}, {@code https} 이거나 {@code about:blank} 인가. 풀지 못하면 받지 않는다. */
    private static boolean allowedAddress(String query) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        String address;
        try {
            address = URLDecoder.decode(query, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return address.startsWith("http://") || address.startsWith("https://") || "about:blank".equals(address);
    }

    /**
     * 브라우저가 보낸 요청이다. 커넥터는 {@code Origin} 도 {@code Sec-Fetch-*} 도 보내지 않는다.
     *
     * <p>브라우저는 no-cors {@code GET} 에 {@code Origin} 을 싣지 않는다. 그래서 {@code Sec-Fetch-Site} 나 {@code Sec-Fetch-Mode}
     * 가 있어도 거절해 페이지가 {@code json/close}, {@code json/activate} 를 부르지 못하게 한다.
     */
    private static boolean fromPage(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.ORIGIN) != null
                || request.getHeader(SEC_FETCH_SITE) != null
                || request.getHeader(SEC_FETCH_MODE) != null;
    }

    private static ResponseEntity<byte[]> empty(HttpStatus status) {
        return ResponseEntity.status(status).build();
    }
}
