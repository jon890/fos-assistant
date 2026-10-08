package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.CdpGatewayHttp;
import com.bifos.assistant.browser.domain.CdpReply;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 브라우저 중계가 CDP 의 HTTP 창구를 부른다.
 *
 * <p>주소는 컨테이너 IP 라서 Chrome 의 {@code Host} 검사를 그대로 통과한다. {@code Origin} 은 보내지 않는다. Chrome 의 상태 코드와 본문은
 * 그대로 돌려준다. 실패 메시지에 응답 본문과 주소를 싣지 않는다.
 */
@Component
public class HttpCdpGateway implements CdpGatewayHttp, AutoCloseable {

    /** 응답 본문을 읽는 상한이다. 탭 목록도 이보다 작다. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    @Override
    public CdpReply get(URI cdp, String path) {
        return send(HttpRequest.newBuilder(endpoint(cdp, path)).GET());
    }

    @Override
    public CdpReply put(URI cdp, String pathAndQuery) {
        return send(HttpRequest.newBuilder(endpoint(cdp, pathAndQuery)).PUT(HttpRequest.BodyPublishers.noBody()));
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    private CdpReply send(HttpRequest.Builder request) {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException ex) {
            throw new IllegalStateException("cdp gateway call failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cdp gateway call interrupted", ex);
        }
        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException ex) {
            throw new IllegalStateException("cdp gateway body could not be read", ex);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalStateException("cdp gateway body is larger than " + MAX_BODY_BYTES + " bytes");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        return new CdpReply(response.statusCode(), contentType, body);
    }

    private static URI endpoint(URI cdp, String pathAndQuery) {
        if (cdp == null || !"http".equals(cdp.getScheme()) || cdp.getHost() == null) {
            throw new IllegalArgumentException("cdp address is not valid");
        }
        if (pathAndQuery == null || !pathAndQuery.startsWith("/")) {
            throw new IllegalArgumentException("cdp gateway path is not valid");
        }
        try {
            return new URI("http://" + cdp.getRawAuthority() + pathAndQuery);
        } catch (URISyntaxException ex) {
            // 메시지에 경로가 들어 있으므로 원인을 잇지 않는다
            throw new IllegalArgumentException("cdp gateway path is not valid");
        }
    }
}
