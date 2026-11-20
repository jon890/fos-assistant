package com.bifos.assistant.chat.artifact.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.artifact.config.LinkImageProperties;
import com.bifos.assistant.chat.artifact.domain.LinkImageRejectedException;
import com.bifos.assistant.chat.artifact.domain.LinkImageRejectedException.Reason;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 링크 사진 받기의 보안 경계와 대표 사진 찾기를 외부 DNS 와 네트워크 없이 본다(ADR-20261120 / link-image-source). */
class LinkImageFetcherTest {
    private static final byte[] PNG = {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};
    private static final byte[] JPEG = {(byte)0xff, (byte)0xd8, (byte)0xff, (byte)0xe0, 1, 2};
    private static final String PUBLIC = "93.184.216.34";

    private final Map<String, String[]> dns = new HashMap<>();
    private final Map<String, byte[]> pages = new HashMap<>();
    private final List<String> requested = new ArrayList<>();


    @Test
    @DisplayName("사진 주소면 그 사진을 바이트 그대로 받고 요청 머리에 Accept 와 User-Agent 를 둔다")
    void fetchesDirectImage() {
        image("https://img.example.com/a.png", "image/png", PNG);
        List<Map<String, String>> headers = new ArrayList<>();
        LinkImageFetcher fetcher = new LinkImageFetcher(properties(List.of(), List.of()), this::resolve, (address, host, request, c) -> {
            headers.add(request.headers());
            return respond(host, request);
        });

        LinkImage image = fetcher.fetch(URI.create("https://img.example.com/a.png"));

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.bytes()).isEqualTo(PNG);
        assertThat(headers.getFirst()).containsKeys("Accept", "User-Agent").containsEntry("Accept-Encoding", "identity");
        assertThat(headers.getFirst()).doesNotContainKeys("Cookie", "Authorization");
    }


    @Test
    @DisplayName("페이지의 대표 사진 meta 를 속성 순서, name, 엔티티와 상관없이 찾아 받는다")
    void fetchesPreviewImageFromPage() {
        image("https://cdn.example.com/p.jpg?w=1&h=2", "image/jpeg", JPEG);
        image("https://shop.example.com/rel/q.jpg", "image/jpeg", JPEG);
        image("https://cdn.example.com/s.jpg", "image/jpeg", JPEG);
        html("https://shop.example.com/a", "<meta content=\"https://cdn.example.com/p.jpg?w=1&amp;h=2\" property=\"og:image\">");
        html("https://shop.example.com/b", "<META NAME='og:image' CONTENT='/rel/q.jpg'>");
        html("https://shop.example.com/c", "<meta name=twitter:image content=//cdn.example.com/s.jpg>");
        html("https://shop.example.com/d",
            "<meta name=\"twitter:image\" content=\"https://cdn.example.com/s.jpg\"><meta property=\"og:image\" content=\"rel/q.jpg\">");
        LinkImageFetcher fetcher = fetcher(List.of(), List.of());

        for (String page : List.of("a", "b", "c")) {
            assertThat(fetcher.fetch(URI.create("https://shop.example.com/" + page)).bytes()).isEqualTo(JPEG);
        }
        requested.clear();
        fetcher.fetch(URI.create("https://shop.example.com/d"));
        assertThat(requested).containsExactly("shop.example.com/d", "shop.example.com/rel/q.jpg");
    }


    @Test
    @DisplayName("넘기기를 5번까지 따라가고 6번째는 막는다. 대표 사진 주소의 넘기기도 함께 센다")
    void followsRedirectsUpToLimit() {
        for (int i = 0; i < 5; i++) {
            redirect("https://s.example.com/" + i, "https://s.example.com/" + (i + 1));
        }
        image("https://s.example.com/5", "image/png", PNG);
        LinkImageFetcher fetcher = fetcher(List.of(), List.of());
        assertThat(fetcher.fetch(URI.create("https://s.example.com/0")).bytes()).isEqualTo(PNG);

        redirect("https://s.example.com/5", "https://s.example.com/6");
        image("https://s.example.com/6", "image/png", PNG);
        assertRejected(fetcher, "https://s.example.com/0", Reason.BLOCKED);

        redirect("https://t.example.com/0", "https://t.example.com/1");
        redirect("https://t.example.com/1", "https://t.example.com/2");
        redirect("https://t.example.com/2", "https://t.example.com/page");
        html("https://t.example.com/page", "<meta property=\"og:image\" content=\"https://t.example.com/r0\">");
        redirect("https://t.example.com/r0", "https://t.example.com/r1");
        redirect("https://t.example.com/r1", "https://t.example.com/r2");
        redirect("https://t.example.com/r2", "https://t.example.com/img");
        image("https://t.example.com/img", "image/png", PNG);
        assertRejected(fetcher, "https://t.example.com/0", Reason.BLOCKED);
    }


    @Test
    @DisplayName("주소 모양, 내부망과 예약 주소, 풀리지 않는 이름, 금지 목록은 모두 BLOCKED 이고 연결하지 않는다")
    void blocksUnsafeAddressesBeforeConnecting() {
        dns.put("loop.example.com", new String[] {"127.0.0.1"});
        dns.put("ten.example.com", new String[] {"10.1.2.3"});
        dns.put("meta.example.com", new String[] {"169.254.169.254"});
        dns.put("home.example.com", new String[] {"192.168.0.10"});
        dns.put("v6.example.com", new String[] {"::1"});
        dns.put("ula.example.com", new String[] {"fd00::1"});
        dns.put("mixed.example.com", new String[] {PUBLIC, "10.0.0.1"});
        dns.put("self.example.org", new String[] {"203.0.113.7"});
        dns.put("cidr.example.com", new String[] {"198.51.100.20"});
        LinkImageFetcher fetcher = new LinkImageFetcher(
            properties(List.of("example.org"), List.of("198.51.100.0/24")),
            this::resolve,
            (address, host, request, c) -> {
                throw new AssertionError("must not connect to " + host);
            });

        for (String link : List.of(
            "http://img.example.com/a.png",
            "https://93.184.216.34/a.png",
            "https://user@img.example.com/a.png",
            "https://img.example.com:8443/a.png",
            "https://loop.example.com/a.png",
            "https://ten.example.com/a.png",
            "https://meta.example.com/a.png",
            "https://home.example.com/a.png",
            "https://v6.example.com/a.png",
            "https://ula.example.com/a.png",
            "https://mixed.example.com/a.png",
            "https://nowhere.example.com/a.png",
            "https://self.example.org/a.png",
            "https://cidr.example.com/a.png")) {
            assertRejected(fetcher, link, Reason.BLOCKED);
        }
    }


    @Test
    @DisplayName("넘기기 끝이 내부망이나 금지 목록이면 BLOCKED 다")
    void blocksRedirectToUnsafeAddress() {
        dns.put("inside.example.com", new String[] {"10.0.0.5"});
        dns.put("self.example.org", new String[] {"203.0.113.7"});
        redirect("https://s.example.com/in", "https://inside.example.com/a.png");
        redirect("https://s.example.com/self", "https://self.example.org/a.png");
        redirect("https://s.example.com/http", "http://img.example.com/a.png");
        LinkImageFetcher fetcher = fetcher(List.of("example.org"), List.of());

        assertRejected(fetcher, "https://s.example.com/in", Reason.BLOCKED);
        assertRejected(fetcher, "https://s.example.com/self", Reason.BLOCKED);
        assertRejected(fetcher, "https://s.example.com/http", Reason.BLOCKED);
        assertThat(requested).noneMatch(entry -> entry.startsWith("inside.") || entry.startsWith("self."));
    }


    @Test
    @DisplayName("사진이 아니거나 형식이 틀리면 NO_IMAGE, 상한을 넘으면 TOO_LARGE 다")
    void rejectsNonImagesAndLargeImages() {
        html("https://shop.example.com/none", "<title>상품</title>");
        image("https://img.example.com/a.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        image("https://img.example.com/a.avif", "image/avif", PNG);
        image("https://img.example.com/fake.png", "image/png", JPEG);
        html("https://shop.example.com/avif", "<meta property=\"og:image\" content=\"https://img.example.com/a.avif\">");
        html("https://shop.example.com/page2", "<meta property=\"og:image\" content=\"https://shop.example.com/none\">");
        image("https://img.example.com/big.png", "image/png", big(64));
        pages.put("img.example.com/liar.png", response(200, Map.of("Content-Type", "image/png"), big(64)));
        LinkImageFetcher fetcher = new LinkImageFetcher(
            new LinkImageProperties(null, null, Duration.ofSeconds(2), null, null, 32, null, List.of(), List.of()),
            this::resolve,
            (address, host, request, c) -> respond(host, request));

        assertRejected(fetcher, "https://shop.example.com/none", Reason.NO_IMAGE);
        assertRejected(fetcher, "https://img.example.com/a.svg", Reason.NO_IMAGE);
        assertRejected(fetcher, "https://img.example.com/a.avif", Reason.NO_IMAGE);
        assertRejected(fetcher, "https://shop.example.com/avif", Reason.NO_IMAGE);
        assertRejected(fetcher, "https://shop.example.com/page2", Reason.NO_IMAGE);
        assertRejected(fetcher, "https://img.example.com/fake.png", Reason.NO_IMAGE);
        assertRejected(fetcher, "https://img.example.com/big.png", Reason.TOO_LARGE);
        assertRejected(fetcher, "https://img.example.com/liar.png", Reason.TOO_LARGE);
    }


    @Test
    @DisplayName("페이지는 앞 상한까지만 읽고 그 뒤의 meta 는 찾지 않는다")
    void readsOnlyPagePrefix() {
        image("https://img.example.com/a.png", "image/png", PNG);
        html("https://shop.example.com/long", "x".repeat(4096) + "<meta property=\"og:image\" content=\"https://img.example.com/a.png\">");
        LinkImageFetcher fetcher = new LinkImageFetcher(
            new LinkImageProperties(null, null, Duration.ofSeconds(2), null, 1024, null, null, List.of(), List.of()),
            this::resolve,
            (address, host, request, c) -> respond(host, request));

        assertRejected(fetcher, "https://shop.example.com/long", Reason.NO_IMAGE);
    }


    @Test
    @DisplayName("200 이 아닌 답, 압축한 답, 연결 실패, 제한 시간은 FAILED 다")
    void failsOnBadResponsesAndTimeout() throws Exception {
        pages.put("shop.example.com/404", response(404, Map.of("Content-Type", "text/html"), new byte[0]));
        pages.put("img.example.com/gz.png", response(200, Map.of("Content-Type", "image/png", "Content-Encoding", "gzip"), PNG));
        pages.put("shop.example.com/noloc", response(302, Map.of(), new byte[0]));
        LinkImageFetcher fetcher = fetcher(List.of(), List.of());
        assertRejected(fetcher, "https://shop.example.com/404", Reason.FAILED);
        assertRejected(fetcher, "https://img.example.com/gz.png", Reason.FAILED);
        assertRejected(fetcher, "https://shop.example.com/noloc", Reason.FAILED);
        assertRejected(fetcher, "https://shop.example.com/missing", Reason.FAILED);

        CountDownLatch never = new CountDownLatch(1);
        LinkImageFetcher slow = new LinkImageFetcher(
            new LinkImageProperties(null, null, Duration.ofMillis(200), null, null, null, null, List.of(), List.of()),
            this::resolve,
            (address, host, request, c) -> {
                try {
                    never.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("interrupted");
            });
        assertRejected(slow, "https://img.example.com/a.png", Reason.FAILED);
        never.countDown();
    }


    private LinkImageFetcher fetcher(List<String> blockedHosts, List<String> blockedCidrs) {
        return new LinkImageFetcher(properties(blockedHosts, blockedCidrs), this::resolve, (address, host, request, c) -> respond(host, request));
    }


    private static LinkImageProperties properties(List<String> blockedHosts, List<String> blockedCidrs) {
        return new LinkImageProperties(null, null, Duration.ofSeconds(2), null, null, null, null, blockedHosts, blockedCidrs);
    }


    private InetAddress[] resolve(String host) throws Exception {
        String[] values = dns.getOrDefault(host, host.startsWith("nowhere.") ? null : new String[] {PUBLIC});
        if (values == null) {
            throw new UnknownHostException(host);
        }
        InetAddress[] addresses = new InetAddress[values.length];
        for (int i = 0; i < values.length; i++) {
            addresses[i] = InetAddress.getByName(values[i]);
        }
        return addresses;
    }


    private ArtifactSourceResponse respond(String host, LinkImageRequest request) throws IOException {
        URI source = request.source();
        String key = host + source.getRawPath() + (source.getRawQuery() == null ? "" : "?" + source.getRawQuery());
        requested.add(key);
        byte[] raw = pages.get(key);
        if (raw == null) {
            throw new IOException("connection refused");
        }
        return ArtifactSourceSocketTransport.parseAnyStatus(new Socket(), new ByteArrayInputStream(raw), request.maxHeaderBytes());
    }


    private void image(String url, String type, byte[] body) {
        pages.put(key(url), response(200, Map.of("Content-Type", type, "Content-Length", String.valueOf(body.length)), body));
    }


    private void html(String url, String body) {
        pages.put(key(url), response(200, Map.of("Content-Type", "text/html; charset=utf-8"), body.getBytes(StandardCharsets.UTF_8)));
    }


    private void redirect(String from, String to) {
        pages.put(key(from), response(302, Map.of("Location", to, "Set-Cookie", "a=1"), new byte[0]));
    }


    private static String key(String url) {
        URI uri = URI.create(url);
        return uri.getHost() + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }


    private static byte[] response(int status, Map<String, String> headers, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");
        out.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body);
        return out.toByteArray();
    }


    private static byte[] big(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy(PNG, 0, bytes, 0, PNG.length);
        return bytes;
    }


    private static void assertRejected(LinkImageFetcher fetcher, String link, Reason reason) {
        assertThatThrownBy(() -> fetcher.fetch(URI.create(link)))
            .as(link)
            .isInstanceOfSatisfying(LinkImageRejectedException.class, ex -> assertThat(ex.reason()).isEqualTo(reason));
    }
}
