package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.ArtifactSourceProperties;
import com.bifos.assistant.chat.infra.ArtifactSourceFetcher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 주소 결과물은 외부 DNS나 네트워크 없이 대역으로 경계를 검사한다. */
class ArtifactSourceFetcherTest {

    @Test
    void 주소_공급자_설정은_안전한_기본값과_형식만_받는다() {
        assertThat(new ArtifactSourceProperties(null, null, null, null).allowedHosts()).isEmpty();
        for (String invalid : List.of("*.example.com", "https://images.example.com", "127.0.0.1", "images.example.com:443", "Images.example.com")) {
            assertThatThrownBy(() -> new ArtifactSourceProperties(List.of(invalid), null, null, null)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new ArtifactSourceProperties(List.of(), Duration.ZERO, null, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 허용한_호스트의_공개_IP와_맞는_MIME_이미지만_받는다() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        ArtifactSourceFetcher fetcher = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> {
                    connects.incrementAndGet();
                    assertThat(address.getHostAddress()).isEqualTo("8.8.8.8");
                    return new ArtifactSourceFetcher.Response(200, Map.of("content-type", "image/png; charset=binary", "content-length", "3"),
                            new ByteArrayInputStream(new byte[] {1, 2, 3}));
                });

        assertThat(fetcher.fetch(URI.create("https://images.example.com/a.png?x=1"), "image/png"))
                .containsExactly(1, 2, 3);
        assertThat(connects).hasValue(1);
    }

    @Test
    void 허용되지_않은_URL과_사설_주소는_DNS_또는_연결하지_않는다() throws Exception {
        AtomicInteger dns = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        ArtifactSourceFetcher rejected = fetcher(host -> { dns.incrementAndGet(); return new InetAddress[] {InetAddress.getByName("8.8.8.8")}; },
                (address, host, source, connect, read, cancellation) -> { connects.incrementAndGet(); throw new AssertionError(); });
        assertThatThrownBy(() -> rejected.fetch(URI.create("http://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rejected.fetch(URI.create("https://127.0.0.1/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rejected.fetch(URI.create("https://images.example.com:0/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> rejected.fetch(URI.create("https://images.example.com.evil/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
        assertThat(dns).hasValue(0);
        assertThat(connects).hasValue(0);

        ArtifactSourceFetcher privateAddress = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")},
                (address, host, source, connect, read, cancellation) -> { connects.incrementAndGet(); throw new AssertionError(); });
        assertThatThrownBy(() -> privateAddress.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
        assertThat(connects).hasValue(0);
    }

    @Test
    void 크기와_형식과_압축_응답을_저장_전에_거절한다() throws Exception {
        ArtifactSourceFetcher tooLarge = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceFetcher.Response(200, Map.of("content-type", "image/png"),
                        new ByteArrayInputStream(new byte[ArtifactSourceFetcher.MAX_BYTES + 1])));
        assertThatThrownBy(() -> tooLarge.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);

        ArtifactSourceFetcher gzip = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceFetcher.Response(200, Map.of("content-type", "image/png", "content-encoding", "gzip"), new ByteArrayInputStream(new byte[0])));
        assertThatThrownBy(() -> gzip.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);

        ArtifactSourceFetcher truncated = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceFetcher.Response(200, Map.of("content-type", "image/png", "content-length", "2"), new ByteArrayInputStream(new byte[] {1})));
        assertThatThrownBy(() -> truncated.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void HTTP_머리글은_실제_CRLF로_끝나야_하고_중복_길이는_거절한다() throws Exception {
        assertThat(new String(ArtifactSourceFetcher.SocketTransport.requestBytes("/a?x=1", "images.example.com")))
                .isEqualTo("GET /a?x=1 HTTP/1.1\r\nHost: images.example.com\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n");
        try (Socket socket = new Socket(); ArtifactSourceFetcher.Response response = ArtifactSourceFetcher.SocketTransport.parse(socket,
                new ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 1\r\n\r\nx".getBytes()))) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers()).containsEntry("content-length", "1");
        }
        assertThatThrownBy(() -> ArtifactSourceFetcher.SocketTransport.parse(new Socket(),
                new ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n".getBytes())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ArtifactSourceFetcher.SocketTransport.parse(new Socket(),
                new ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\n".getBytes())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ArtifactSourceFetcher.SocketTransport.parse(new Socket(),
                new ByteArrayInputStream(("HTTP/1.1 200 OK\r\nX: " + "x".repeat(16 * 1024) + "\r\n\r\n").getBytes())))
                .isInstanceOf(IOException.class);
        for (String header : List.of("Content-Encoding : gzip", "Transfer-Encoding : chunked")) {
            assertThatThrownBy(() -> ArtifactSourceFetcher.SocketTransport.parse(new Socket(),
                    new ByteArrayInputStream(("HTTP/1.1 200 OK\r\n" + header + "\r\n\r\n").getBytes())))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void chunked와_고정_길이_응답을_프레이밍에_맞게_읽고_오류와_redirect는_거절한다() throws Exception {
        ArtifactSourceFetcher chunked = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> parsed("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"));
        assertThat(chunked.fetch(URI.create("https://images.example.com/a.png"), "image/png")).containsExactly('a', 'b', 'c');
        ArtifactSourceFetcher fixed = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> parsed("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 3\r\n\r\nabcstill-open"));
        assertThat(fixed.fetch(URI.create("https://images.example.com/a.png"), "image/png")).containsExactly('a', 'b', 'c');
        for (String response : List.of(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\n500001\r\nx",
                "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/a\r\n\r\n")) {
            ArtifactSourceFetcher invalid = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                    (address, host, source, connect, read, cancellation) -> parsed(response));
            assertThatThrownBy(() -> invalid.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void 혼합_A_AAAA와_느린_DNS는_전송하지_않는다() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        for (String blocked : List.of("fd00::1", "fec0::1", "feff::1", "::ffff:127.0.0.1")) {
            ArtifactSourceFetcher mixed = fetcher(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName(blocked)},
                    (address, host, source, connect, read, cancellation) -> { connects.incrementAndGet(); throw new AssertionError(); });
            assertThatThrownBy(() -> mixed.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
            assertThat(connects).hasValue(0);
        }
        ArtifactSourceFetcher slowDns = new ArtifactSourceFetcher(new ArtifactSourceProperties(List.of("images.example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(20)),
                host -> { Thread.sleep(200); return new InetAddress[] {InetAddress.getByName("8.8.8.8")}; },
                (address, host, source, connect, read, cancellation) -> { throw new AssertionError(); });
        assertThatThrownBy(() -> slowDns.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 전체_제한_시간이_지나면_진행_중인_전송을_닫는다() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        ArtifactSourceFetcher fetcher = new ArtifactSourceFetcher(new ArtifactSourceProperties(List.of("images.example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(20)),
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                (address, host, source, connect, read, cancellation) -> { cancellation.register(closed::countDown); try { Thread.sleep(200); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } throw new IOException("late"); });
        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://images.example.com/a.png"), "image/png")).isInstanceOf(RuntimeException.class);
        assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void DNS_네_개가_멈추면_다음_호출은_대기열에_넣지_않고_즉시_거절한다() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        ArtifactSourceFetcher blocked = new ArtifactSourceFetcher(new ArtifactSourceProperties(List.of("images.example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2)),
                host -> { started.countDown(); release.await(); return new InetAddress[] {InetAddress.getByName("8.8.8.8")}; },
                (address, host, source, connect, read, cancellation) -> new ArtifactSourceFetcher.Response(200, Map.of("content-type", "image/png", "content-length", "0"), new ByteArrayInputStream(new byte[0])));
        List<Thread> callers = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> Thread.startVirtualThread(() -> blocked.fetch(URI.create("https://images.example.com/a.png"), "image/png")))
                .toList();
        try {
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> blocked.fetch(URI.create("https://images.example.com/a.png"), "image/png"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(500));
        } finally {
            release.countDown();
        }
        for (Thread caller : callers) caller.join(1000);
    }

    private static ArtifactSourceFetcher.Response parsed(String response) throws IOException {
        return ArtifactSourceFetcher.SocketTransport.parse(new Socket(), new ByteArrayInputStream(response.getBytes()));
    }

    private static ArtifactSourceFetcher fetcher(ArtifactSourceFetcher.DnsResolver dns, ArtifactSourceFetcher.Transport transport) {
        return new ArtifactSourceFetcher(new ArtifactSourceProperties(List.of("images.example.com"), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2)), dns, transport);
    }
}
