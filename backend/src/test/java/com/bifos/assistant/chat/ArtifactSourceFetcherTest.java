package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.infra.ArtifactSourceDnsResolver;
import com.bifos.assistant.chat.infra.ArtifactSourceResponse;
import com.bifos.assistant.chat.infra.ArtifactSourceSocketTransport;
import com.bifos.assistant.chat.infra.ArtifactSourceTransport;
import com.bifos.assistant.chat.infra.ArtifactSourceFetcher;
import com.bifos.assistant.chat.infra.ArtifactSourceProperties;
import com.bifos.assistant.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 주소 결과물은 외부 DNS나 네트워크 없이 대역으로 경계를 검사한다. */
class ArtifactSourceFetcherTest {

    @Test
    @DisplayName("주소 공급자 설정은 안전한 기본값과 형식만 받는다")
    void acceptsOnlySafeDefaultsAndFormatsForAddressProviderConfig() {
        assertThat(new ArtifactSourceProperties(null, null, null, null).allowedHosts())
                .isEmpty();
        for (String invalid : List.of(
                "*.example.com",
                "https://images.example.com",
                "127.0.0.1",
                "images.example.com:443",
                "Images.example.com")) {
            assertThatThrownBy(() -> new ArtifactSourceProperties(List.of(invalid), null, null, null))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new ArtifactSourceProperties(List.of(), Duration.ZERO, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈 허용 목록과 userinfo는 DNS 전에 거절한다")
    void rejectsEmptyAllowlistAndUserinfoBeforeDns() throws Exception {
        AtomicInteger dns = new AtomicInteger();
        ArtifactSourceFetcher empty = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(List.of(), null, null, null),
                host -> {
                    dns.incrementAndGet();
                    return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
                },
                (address, host, source, connect, read, cancellation) -> {
                    throw new AssertionError();
                });
        assertThatThrownBy(() -> empty.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        ArtifactSourceFetcher allowed = fetcher(
                host -> {
                    dns.incrementAndGet();
                    return new InetAddress[0];
                },
                (address, host, source, connect, read, cancellation) -> {
                    throw new AssertionError();
                });
        assertThatThrownBy(() -> allowed.fetch(URI.create("https://user@images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThat(dns).hasValue(0);
    }

    @Test
    @DisplayName("허용한 호스트의 공개 IP와 맞는 MIME 이미지만 받는다")
    void acceptsOnlyPublicIpOfAllowedHostWithMatchingImageMime() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        ArtifactSourceFetcher fetcher = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> {
                    connects.incrementAndGet();
                    assertThat(address.getHostAddress()).isEqualTo("8.8.8.8");
                    return new ArtifactSourceResponse(
                            200,
                            Map.of("content-type", "image/png; charset=binary", "content-length", "3"),
                            new ByteArrayInputStream(new byte[] {1, 2, 3}));
                });

        assertThat(fetcher.fetch(URI.create("https://images.example.com/a.png?x=1"), "image/png"))
                .containsExactly(1, 2, 3);
        assertThat(connects).hasValue(1);
    }

    @Test
    @DisplayName("공개 주소와 특수 용도 주소의 경계를 구분한다")
    void distinguishesPublicFromSpecialPurposeAddressBoundaries() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        for (String address : List.of("192.0.1.1", "192.0.0.9", "192.0.0.10", "192.31.196.1", "192.52.193.1")) {
            ArtifactSourceFetcher publicAddress = fetcher(
                    host -> new InetAddress[] {InetAddress.getByName(address)},
                    (resolved, host, source, connect, read, cancellation) -> {
                        connects.incrementAndGet();
                        return new ArtifactSourceResponse(
                                200, Map.of("content-type", "image/png"), new ByteArrayInputStream(new byte[] {1}));
                    });
            assertThat(publicAddress.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                    .containsExactly(1);
        }
        ArtifactSourceFetcher reserved = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("192.0.0.1")},
                (resolved, host, source, connect, read, cancellation) -> {
                    throw new AssertionError();
                });
        assertThatThrownBy(() -> reserved.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThat(connects).hasValue(5);
    }

    @Test
    @DisplayName("허용되지 않은 URL과 사설 주소는 DNS 또는 연결하지 않는다")
    void skipsDnsAndConnectForDisallowedUrlAndPrivateAddress() throws Exception {
        AtomicInteger dns = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        ArtifactSourceFetcher rejected = fetcher(
                host -> {
                    dns.incrementAndGet();
                    return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
                },
                (address, host, source, connect, read, cancellation) -> {
                    connects.incrementAndGet();
                    throw new AssertionError();
                });
        assertThatThrownBy(() -> rejected.fetch(URI.create("http://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rejected.fetch(URI.create("https://127.0.0.1/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rejected.fetch(URI.create("https://images.example.com:0/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rejected.fetch(URI.create("https://images.example.com.evil/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThat(dns).hasValue(0);
        assertThat(connects).hasValue(0);

        ArtifactSourceFetcher privateAddress = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")},
                (address, host, source, connect, read, cancellation) -> {
                    connects.incrementAndGet();
                    throw new AssertionError();
                });
        assertThatThrownBy(() -> privateAddress.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThat(connects).hasValue(0);
    }

    @Test
    @DisplayName("크기와 형식과 압축 응답을 저장 전에 거절한다")
    void rejectsSizeFormatAndCompressedResponseBeforeSaving() throws Exception {
        ArtifactSourceFetcher tooLarge = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceResponse(
                        200,
                        Map.of("content-type", "image/png"),
                        new ByteArrayInputStream(new byte[ArtifactSourceFetcher.MAX_BYTES + 1])));
        assertThatThrownBy(() -> tooLarge.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);

        ArtifactSourceFetcher gzip = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceResponse(
                        200,
                        Map.of("content-type", "image/png", "content-encoding", "gzip"),
                        new ByteArrayInputStream(new byte[0])));
        assertThatThrownBy(() -> gzip.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);

        ArtifactSourceFetcher truncated = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceResponse(
                        200,
                        Map.of("content-type", "image/png", "content-length", "2"),
                        new ByteArrayInputStream(new byte[] {1})));
        assertThatThrownBy(() -> truncated.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("이미지 확장자별 MIME와 정확히 5MiB 본문만 받고 실패 응답은 닫는다")
    void acceptsPerExtensionMimeAndExactly5MiBBodyAndClosesOnFailure() throws Exception {
        Map<String, String> types = Map.of(
                "png",
                "image/png",
                "jpg",
                "image/jpeg",
                "jpeg",
                "image/jpeg",
                "gif",
                "image/gif",
                "webp",
                "image/webp");
        for (var entry : types.entrySet()) {
            ArtifactSourceFetcher fetcher = fetcher(
                    host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                    (address, host, source, connect, read, cancellation) -> new ArtifactSourceResponse(
                            200,
                            Map.of("content-type", entry.getValue(), "content-length", "1"),
                            new ByteArrayInputStream(new byte[] {1})));
            assertThat(fetcher.fetch(URI.create("https://images.example.com/a." + entry.getKey()), entry.getValue()))
                    .containsExactly(1);
        }
        AtomicInteger closed = new AtomicInteger();
        ArtifactSourceFetcher mismatch = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceResponse(
                        200, Map.of("content-type", "text/html"), new ByteArrayInputStream(new byte[0]) {
                            @Override
                            public void close() {
                                closed.incrementAndGet();
                            }
                        }));
        assertThatThrownBy(() -> mismatch.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThat(closed).hasValue(1);
        ArtifactSourceFetcher limit = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceResponse(
                        200,
                        Map.of(
                                "content-type",
                                "image/png",
                                "content-length",
                                String.valueOf(ArtifactSourceFetcher.MAX_BYTES)),
                        new ByteArrayInputStream(new byte[ArtifactSourceFetcher.MAX_BYTES])));
        assertThat(limit.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .hasSize(ArtifactSourceFetcher.MAX_BYTES);
    }

    @Test
    @DisplayName("잘못된 MIME와 초과 길이는 본문을 읽기 전에 거절한다")
    void rejectsWrongMimeAndOversizeLengthBeforeReadingBody() throws Exception {
        for (Map<String, String> headers : List.<Map<String, String>>of(
                Map.of(),
                Map.of("content-type", "image/jpeg"),
                Map.of("content-type", "text/html"),
                Map.of("content-type", "image/svg+xml"),
                Map.of(
                        "content-type",
                        "image/png",
                        "content-length",
                        String.valueOf(ArtifactSourceFetcher.MAX_BYTES + 1)))) {
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            ArtifactSourceFetcher invalid = fetcher(
                    host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                    (address, host, source, connect, read, cancellation) ->
                            new ArtifactSourceResponse(200, headers, new ByteArrayInputStream(new byte[] {1}) {
                                @Override
                                public synchronized int read(byte[] bytes, int offset, int length) {
                                    reads.incrementAndGet();
                                    return super.read(bytes, offset, length);
                                }

                                @Override
                                public void close() {
                                    closes.incrementAndGet();
                                }
                            }));
            assertThatThrownBy(() -> invalid.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(reads).hasValue(0);
            assertThat(closes).hasValue(1);
        }
    }

    @Test
    @DisplayName("HTTP 머리글은 실제 CRLF로 끝나야 하고 중복 길이는 거절한다")
    void requiresRealCrlfInHttpHeadersAndRejectsDuplicateLength() throws Exception {
        assertThat(new String(ArtifactSourceSocketTransport.requestBytes("/a?x=1", "images.example.com")))
                .isEqualTo(
                        "GET /a?x=1 HTTP/1.1\r\nHost: images.example.com\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n");
        try (Socket socket = new Socket();
                ArtifactSourceResponse response = ArtifactSourceSocketTransport.parse(
                        socket,
                        new ByteArrayInputStream(
                                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 1\r\n\r\nx"
                                        .getBytes()))) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers()).containsEntry("content-length", "1");
        }
        assertThatThrownBy(() -> ArtifactSourceSocketTransport.parse(
                        new Socket(),
                        new ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n".getBytes())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ArtifactSourceSocketTransport.parse(
                        new Socket(),
                        new ByteArrayInputStream(
                                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\n".getBytes())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ArtifactSourceSocketTransport.parse(
                        new Socket(),
                        new ByteArrayInputStream(
                                ("HTTP/1.1 200 OK\r\nX: " + "x".repeat(16 * 1024) + "\r\n\r\n").getBytes())))
                .isInstanceOf(IOException.class);
        for (String header : List.of("Content-Encoding : gzip", "Transfer-Encoding : chunked")) {
            assertThatThrownBy(() -> ArtifactSourceSocketTransport.parse(
                            new Socket(),
                            new ByteArrayInputStream(("HTTP/1.1 200 OK\r\n" + header + "\r\n\r\n").getBytes())))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    @DisplayName("chunked와 고정 길이 응답을 프레이밍에 맞게 읽고 오류와 redirect는 거절한다")
    void readsChunkedAndFixedLengthByFramingAndRejectsErrorAndRedirect() throws Exception {
        ArtifactSourceFetcher chunked = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> parsed(
                        "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"));
        assertThat(chunked.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .containsExactly('a', 'b', 'c');
        ArtifactSourceFetcher fixed = fetcher(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) ->
                        parsed("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 3\r\n\r\nabcstill-open"));
        assertThat(fixed.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .containsExactly('a', 'b', 'c');
        for (String response : List.of(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\n500001\r\nx",
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n"
                        + ("X: " + "x".repeat(1000) + "\r\n").repeat(20) + "\r\n",
                "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/a\r\n\r\n")) {
            ArtifactSourceFetcher invalid = fetcher(
                    host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                    (address, host, source, connect, read, cancellation) -> parsed(response));
            assertThatThrownBy(() -> invalid.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @DisplayName("혼합 A AAAA와 느린 DNS는 전송하지 않는다")
    void doesNotSendForMixedARecordsAndSlowDns() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        for (String blocked : List.of("fd00::1", "fec0::1", "feff::1", "::ffff:127.0.0.1")) {
            ArtifactSourceFetcher mixed = fetcher(
                    host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName(blocked)},
                    (address, host, source, connect, read, cancellation) -> {
                        connects.incrementAndGet();
                        throw new AssertionError();
                    });
            assertThatThrownBy(() -> mixed.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(connects).hasValue(0);
        }
        ArtifactSourceFetcher slowDns = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(
                        List.of("images.example.com"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(20)),
                host -> {
                    Thread.sleep(200);
                    return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
                },
                (address, host, source, connect, read, cancellation) -> {
                    throw new AssertionError();
                });
        assertThatThrownBy(() -> slowDns.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("전체 제한 시간이 지나면 진행 중인 전송을 닫는다")
    void closesInFlightTransferAfterTotalTimeout() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        ArtifactSourceFetcher fetcher = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(
                        List.of("images.example.com"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(20)),
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> {
                    cancellation.register(closed::countDown);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IOException("late");
                });
        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                .isInstanceOf(RuntimeException.class);
        assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("DNS 네 개가 멈추면 다음 호출은 대기열에 넣지 않고 즉시 거절한다")
    void rejectsNextCallImmediatelyWhenFourDnsLookupsHang() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        ArtifactSourceFetcher blocked = new ArtifactSourceFetcher(
                new ArtifactSourceProperties(
                        List.of("images.example.com"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10)),
                host -> {
                    started.countDown();
                    release.await();
                    return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
                },
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceResponse(
                        200,
                        Map.of("content-type", "image/png", "content-length", "0"),
                        new ByteArrayInputStream(new byte[0])));
        List<Thread> callers = IntStream.range(0, 4)
                .mapToObj(index -> Thread.startVirtualThread(
                        () -> blocked.fetch(URI.create("https://images.example.com/a.png"), "image/png")))
                .toList();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> blocked.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            ex -> assertThat(ex.getMessage()).isEqualTo("artifact source download is busy"));
        } finally {
            release.countDown();
        }
        for (Thread caller : callers) {
            caller.join(5000);
            assertThat(caller.isAlive()).isFalse();
        }
    }

    private static ArtifactSourceResponse parsed(String response) throws IOException {
        return ArtifactSourceSocketTransport.parse(new Socket(), new ByteArrayInputStream(response.getBytes()));
    }

    private static ArtifactSourceFetcher fetcher(
            ArtifactSourceDnsResolver dns, ArtifactSourceTransport transport) {
        return new ArtifactSourceFetcher(
                new ArtifactSourceProperties(
                        List.of("images.example.com"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2)),
                dns,
                transport);
    }
}
