package com.bifos.assistant.notification.infra;

import com.bifos.assistant.notification.config.PushProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * JDK {@link HttpClient} 로 웹 푸시를 보내는 운영 구현이다(ADR-20261120 / web-push).
 *
 * <p>redirect 를 따르지 않는다. 허용한 푸시 서비스가 다른 곳으로 넘겨도 그 주소로 가지 않는다. 머리는 {@code TTL: 86400},
 * {@code Urgency: normal}, {@code Content-Encoding: aes128gcm} 이다.
 */
@Component
public class HttpWebPushClient implements WebPushClient {

    static final String TTL_SECONDS = "86400";

    private final HttpClient http;
    private final Duration requestTimeout;


    public HttpWebPushClient(PushProperties properties) {
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.connectTimeout())
            .build();
        this.requestTimeout = properties.requestTimeout();
    }


    @Override
    public PushResponse send(String endpoint, String authorization, byte[] body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(requestTimeout)
            .header("Authorization", authorization)
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("TTL", TTL_SECONDS)
            .header("Urgency", "normal")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return new PushResponse(response.statusCode(), retryAfter(response));
        } catch (IOException ex) {
            return PushResponse.unreachable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return PushResponse.unreachable();
        }
    }


    private static Duration retryAfter(HttpResponse<Void> response) {
        return response.headers().firstValue("Retry-After")
            .map(String::strip)
            .filter(value -> value.matches("\\d{1,6}"))
            .map(value -> Duration.ofSeconds(Long.parseLong(value)))
            .orElse(null);
    }
}
