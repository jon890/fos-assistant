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
    /** AVIF 머리(ftyp avif)다. 받는 네 형식이 아니라 머리 바이트로 판정해도 받지 않는다. */
    private static final byte[] AVIF = {0, 0, 0, 0x1c, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'};
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
    @DisplayName("이름을 푼 공개 IP 에 고정해 연결하고 SNI 와 Host 는 원래 이름을 쓴다")
    void pinsConnectionToResolvedAddress() {
        dns.put("img.example.com", new String[] {"93.184.216.40"});
        image("https://img.example.com/a.png", "image/png", PNG);
        List<String> connections = new ArrayList<>();
        LinkImageFetcher fetcher = new LinkImageFetcher(properties(List.of(), List.of()), this::resolve, (address, host, request, c) -> {
            connections.add(address.getHostAddress() + " " + host);
            return respond(host, request);
        });

        fetcher.fetch(URI.create("https://img.example.com/a.png"));

        assertThat(connections).containsExactly("93.184.216.40 img.example.com");
    }


    @Test
    @DisplayName("동시 받기가 차면 다섯째 링크는 FAILED 다")
    void rejectsWhenAllWorkersAreBusy() throws Exception {
        image("https://img.example.com/a.png", "image/png", PNG);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        LinkImageFetcher fetcher = new LinkImageFetcher(properties(List.of(), List.of()), this::resolve, (address, host, request, c) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return respond(host, request);
        });
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread caller = new Thread(() -> fetcher.fetch(URI.create("https://img.example.com/a.png")));
            caller.start();
            callers.add(caller);
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertRejected(fetcher, "https://img.example.com/a.png", Reason.FAILED);
        release.countDown();
        for (Thread caller : callers) {
            caller.join(5000);
        }
    }


    @Test
    @DisplayName("금지 목록이 비었고 그래도 받으라고 켜지 않았으면 연결하지 않고 BLOCKED 다")
    void blocksEverythingWithoutBlocklist() {
        image("https://img.example.com/a.png", "image/png", PNG);
        LinkImageFetcher fetcher = new LinkImageFetcher(
            new LinkImageProperties(null, null, Duration.ofSeconds(2), null, null, null, null, List.of(), List.of(), null),
            this::resolve,
            (address, host, request, c) -> {
                throw new AssertionError("must not connect to " + host);
            });

        assertRejected(fetcher, "https://img.example.com/a.png", Reason.BLOCKED);
    }


    @Test
    @DisplayName("닫히지 않은 meta 로 채운 2MiB 페이지도 제한 시간 안에 NO_IMAGE 로 끝난다")
    void unclosedMetaFloodFinishesQuickly() {
        html("https://shop.example.com/flood", "<meta ".repeat(2 * 1024 * 1024 / 6) + ">");
        html("https://shop.example.com/flood-open", "<meta ".repeat(2 * 1024 * 1024 / 6));
        LinkImageFetcher fetcher = fetcher(List.of(), List.of());

        long started = System.nanoTime();
        assertRejected(fetcher, "https://shop.example.com/flood", Reason.NO_IMAGE);
        assertRejected(fetcher, "https://shop.example.com/flood-open", Reason.NO_IMAGE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
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
    @DisplayName("이미 고른 사진 주소도 이동을 다시 검사하고 페이지 응답이나 너무 큰 사진을 거절한다")
    void checksDirectImageRedirectTypeAndSize() {
        dns.put("inside.example.com", new String[] {"10.0.0.5"});
        redirect("https://cdn.example.com/redirect", "https://inside.example.com/a.png");
        html("https://cdn.example.com/page", "<meta property=\"og:image\" content=\"https://cdn.example.com/other.png\">");
        image("https://cdn.example.com/big", "image/png", big(64));
        LinkImageFetcher fetcher = new LinkImageFetcher(
            new LinkImageProperties(null, null, Duration.ofSeconds(2), null, null, 32, null, List.of(), List.of(), true),
            this::resolve,
            (address, host, request, c) -> respond(host, request));

        assertThatThrownBy(() -> fetcher.fetchDirect(URI.create("https://cdn.example.com/redirect")))
            .isInstanceOfSatisfying(LinkImageRejectedException.class, ex -> assertThat(ex.reason()).isEqualTo(Reason.BLOCKED));
        assertThatThrownBy(() -> fetcher.fetchDirect(URI.create("https://cdn.example.com/page")))
            .isInstanceOfSatisfying(LinkImageRejectedException.class, ex -> assertThat(ex.reason()).isEqualTo(Reason.NO_IMAGE));
        assertThatThrownBy(() -> fetcher.fetchDirect(URI.create("https://cdn.example.com/big")))
            .isInstanceOfSatisfying(LinkImageRejectedException.class, ex -> assertThat(ex.reason()).isEqualTo(Reason.TOO_LARGE));
        assertThat(requested).containsExactly("cdn.example.com/redirect", "cdn.example.com/page", "cdn.example.com/big");
    }


    @Test
    @DisplayName("사진이 아니거나 형식이 틀리면 NO_IMAGE, 상한을 넘으면 TOO_LARGE 다")
    void rejectsNonImagesAndLargeImages() {
        html("https://shop.example.com/none", "<title>상품</title>");
        image("https://img.example.com/a.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        image("https://img.example.com/a.avif", "image/avif", AVIF);
        image("https://img.example.com/fake.png", "image/png", JPEG);
        html("https://shop.example.com/avif", "<meta property=\"og:image\" content=\"https://img.example.com/a.avif\">");
        html("https://shop.example.com/page2", "<meta property=\"og:image\" content=\"https://shop.example.com/none\">");
        image("https://img.example.com/big.png", "image/png", big(64));
        pages.put("img.example.com/liar.png", response(200, Map.of("Content-Type", "image/png"), big(64)));
        LinkImageFetcher fetcher = new LinkImageFetcher(
            new LinkImageProperties(null, null, Duration.ofSeconds(2), null, null, 32, null, List.of(), List.of(), true),
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
    @DisplayName("응답 종류를 사진이 아닌 이름으로 적은 사진은 머리 바이트로 형식을 정해 받는다. 첫 주소와 대표 사진 주소 모두")
    void sniffsImageWithWrongContentType() {
        image("https://img.example.com/form.jpg", "multipart/form-data", JPEG);
        image("https://img.example.com/octet.jpg", "application/octet-stream", JPEG);
        pages.put("img.example.com/untyped.png", response(200, Map.of(), PNG));
        html("https://shop.example.com/form", "<meta property=\"og:image\" content=\"https://img.example.com/form.jpg\">");
        html("https://shop.example.com/octet", "<meta property=\"og:image\" content=\"https://img.example.com/octet.jpg\">");
        LinkImageFetcher fetcher = fetcher(List.of(), List.of());

        for (String link : List.of(
            "https://img.example.com/form.jpg",
            "https://img.example.com/octet.jpg",
            "https://shop.example.com/form",
            "https://shop.example.com/octet")) {
            LinkImage image = fetcher.fetch(URI.create(link));
            assertThat(image.contentType()).as(link).isEqualTo("image/jpeg");
            assertThat(image.bytes()).as(link).isEqualTo(JPEG);
        }
        LinkImage untyped = fetcher.fetch(URI.create("https://img.example.com/untyped.png"));
        assertThat(untyped.contentType()).isEqualTo("image/png");
    }


    @Test
    @DisplayName("응답 종류가 사진이 아니고 머리 바이트도 받는 사진 형식이 아니면 NO_IMAGE 다. 첫 주소와 대표 사진 주소 모두")
    void rejectsUntypedNonImageBytes() {
        byte[] page = "<!doctype html><html><body>상품</body></html>".getBytes(StandardCharsets.UTF_8);
        image("https://img.example.com/html.bin", "application/octet-stream", page);
        pages.put("img.example.com/untyped", response(200, Map.of(), page));
        image("https://img.example.com/form.svg", "multipart/form-data", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));
        image("https://img.example.com/form.avif", "multipart/form-data", AVIF);
        html("https://shop.example.com/html", "<meta property=\"og:image\" content=\"https://img.example.com/html.bin\">");
        html("https://shop.example.com/untyped", "<meta property=\"og:image\" content=\"https://img.example.com/untyped\">");
        html("https://shop.example.com/svg", "<meta property=\"og:image\" content=\"https://img.example.com/form.svg\">");
        LinkImageFetcher fetcher = fetcher(List.of(), List.of());

        for (String link : List.of(
            "https://img.example.com/html.bin",
            "https://img.example.com/untyped",
            "https://img.example.com/form.svg",
            "https://img.example.com/form.avif",
            "https://shop.example.com/html",
            "https://shop.example.com/untyped",
            "https://shop.example.com/svg")) {
            assertRejected(fetcher, link, Reason.NO_IMAGE);
        }
    }


    @Test
    @DisplayName("소문자로 바꾸면 길이가 달라지는 글자(U+0130)가 앞에 있어도 대표 사진 meta 를 원문 그대로 읽는다")
    void readsPreviewAfterLengthChangingCharacters() {
        image("https://img.example.com/a.png", "image/png", PNG);
        html("https://shop.example.com/turkish",
            "<title>" + "\u0130".repeat(40) + "</title><META property=\"og:image\" content=\"https://img.example.com/a.png\">");
        LinkImageFetcher fetcher = fetcher(List.of(), List.of());

        assertThat(fetcher.fetch(URI.create("https://shop.example.com/turkish")).bytes()).isEqualTo(PNG);
    }


    @Test
    @DisplayName("페이지는 앞 상한까지만 읽고 그 뒤의 meta 는 찾지 않는다")
    void readsOnlyPagePrefix() {
        image("https://img.example.com/a.png", "image/png", PNG);
        html("https://shop.example.com/long", "x".repeat(4096) + "<meta property=\"og:image\" content=\"https://img.example.com/a.png\">");
        LinkImageFetcher fetcher = new LinkImageFetcher(
            new LinkImageProperties(null, null, Duration.ofSeconds(2), null, 1024, null, null, List.of(), List.of(), true),
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
            new LinkImageProperties(null, null, Duration.ofMillis(200), null, null, null, null, List.of(), List.of(), true),
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
        return new LinkImageProperties(null, null, Duration.ofSeconds(2), null, null, null, null, blockedHosts, blockedCidrs, true);
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
