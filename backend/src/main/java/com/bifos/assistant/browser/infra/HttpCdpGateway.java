package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.CdpGatewayHttp;
import com.bifos.assistant.browser.domain.CdpReply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 브라우저 중계가 CDP 의 HTTP 창구를 부른다.
 *
 * <p>주소는 컨테이너 IP 라서 Chrome 의 {@code Host} 검사를 그대로 통과한다. {@code Origin} 은 보내지 않는다. Chrome 의 상태 코드와 본문은
 * 그대로 돌려준다. 실패 메시지에 응답 본문과 주소를 싣지 않는다.
 *
 * <p>머리를 받고 본문을 다 읽기까지를 한 시간 제한으로 묶는다. 머리만 보내고 본문을 멈춘 답에 요청 스레드가 묶이지 않는다.
 */
@Component
public class HttpCdpGateway implements CdpGatewayHttp, AutoCloseable {

    /** 응답 본문을 읽는 상한이다. 탭 목록도 이보다 작다. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final Duration requestTimeout;

    public HttpCdpGateway() {
        this(REQUEST_TIMEOUT);
    }

    /** 시험이 시간 제한을 줄여 쓴다. */
    HttpCdpGateway(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

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
        CompletableFuture<HttpResponse<byte[]>> pending =
                client.sendAsync(request.timeout(requestTimeout).build(), info -> new CappedBody());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // 받던 교환을 끊는다
            pending.cancel(true);
            throw new IllegalStateException("cdp gateway call timed out", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof BodyTooLarge) {
                throw new IllegalStateException("cdp gateway body is larger than " + MAX_BODY_BYTES + " bytes");
            }
            throw new IllegalStateException("cdp gateway call failed", ex.getCause());
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cdp gateway call interrupted", ex);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        return new CdpReply(response.statusCode(), contentType, response.body());
    }

    /** 본문이 상한을 넘었다. 주소와 본문을 싣지 않는다. */
    private static final class BodyTooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLarge() {
            super("cdp gateway body is larger than " + MAX_BODY_BYTES + " bytes");
        }
    }

    /** 본문을 상한까지만 모은다. 넘으면 받기를 끊고 {@link BodyTooLarge} 로 끝낸다. */
    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (bytes.size() + item.remaining() > MAX_BODY_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new BodyTooLarge());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
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
