package com.bifos.assistant.chat.artifact.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 링크 사진 받기의 응답 해석을 본다. 스티커 받기의 해석은 {@code ArtifactSourceFetcherTest} 가 본다. */
class ArtifactSourceResponseParseTest {

    @Test
    @DisplayName("넘기기 응답은 상태와 Location 을 돌려준다")
    void returnsRedirectStatusAndLocation() throws Exception {
        try (ArtifactSourceResponse response = parse("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\n\r\n", 1024)) {
            assertThat(response.status()).isEqualTo(302);
            assertThat(response.headers()).containsEntry("location", "/next");
        }
    }


    @Test
    @DisplayName("여러 줄의 Set-Cookie 는 처음 값만 남기고 받는다")
    void keepsFirstOfRepeatedSetCookie() throws Exception {
        try (ArtifactSourceResponse response = parse(
            "HTTP/1.1 200 OK\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Type: text/html\r\nContent-Length: 1\r\n\r\nx", 1024)) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers()).containsEntry("set-cookie", "a=1").containsEntry("content-type", "text/html");
            assertThat(response.body().readAllBytes()).isEqualTo("x".getBytes(StandardCharsets.US_ASCII));
        }
    }


    @Test
    @DisplayName("겹치면 안 되는 머리가 두 번 오면 거절한다")
    void rejectsRepeatedSingleHeaders() {
        for (String header : new String[] {"Content-Length: 1", "Location: /a", "Content-Type: image/png"}) {
            assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\n" + header + "\r\n" + header + "\r\n\r\n", 1024))
                .isInstanceOf(IOException.class);
        }
    }


    @Test
    @DisplayName("머리 상한을 넘거나 상태 줄이 틀리면 거절한다")
    void rejectsLargeHeadersAndBadStatusLine() {
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\nX: " + "x".repeat(2048) + "\r\n\r\n", 1024))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("HTTP/2 200 OK\r\n\r\n", 1024)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n", 1024))
            .isInstanceOf(IOException.class);
    }


    @Test
    @DisplayName("요청 머리를 덧붙이고 CR, LF 가 든 값은 거절한다")
    void appendsRequestHeadersAndRejectsLineBreaks() {
        String request = new String(
            ArtifactSourceSocketTransport.requestBytes("/a", "shop.example.com", Map.of("Accept", "image/png")),
            StandardCharsets.US_ASCII);
        assertThat(request).isEqualTo("GET /a HTTP/1.1\r\nHost: shop.example.com\r\nAccept: image/png\r\nConnection: close\r\n\r\n");
        assertThatThrownBy(() -> ArtifactSourceSocketTransport.requestBytes("/a", "shop.example.com", Map.of("X", "a\r\nB: c")))
            .isInstanceOf(IllegalArgumentException.class);
    }


    private static ArtifactSourceResponse parse(String raw, int maxHeaderBytes) throws IOException {
        return ArtifactSourceSocketTransport.parseAnyStatus(
            new Socket(), new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)), maxHeaderBytes);
    }
}
