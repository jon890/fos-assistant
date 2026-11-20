package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.config.LinkImageProperties;
import com.bifos.assistant.chat.artifact.domain.ArtifactSourceRejectedException;
import com.bifos.assistant.chat.artifact.domain.LinkImageRejectedException;
import com.bifos.assistant.chat.artifact.domain.LinkImageRejectedException.Reason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 사용자가 대화에 보낸 링크의 사진을 받는다(ADR-20261120 / link-image-source).
 *
 * <p>허용 주소 목록은 없다. 대신 넘어가는 주소마다 주소 검사({@link ArtifactSourceFetcher#checkSource}), 금지 목록, 이름을 푼 모든
 * IP 의 공개 판정({@link ArtifactSourceFetcher#isPublic})을 하고 그중 하나에 고정해 연결한다. 웹 페이지면 대표 사진 meta 를 찾아 한 번 더
 * 받는다. 받은 바이트는 저장하지 않는다.
 *
 * <p>로그에는 호스트와 까닭만 남기고 경로와 쿼리는 남기지 않는다. 사용자가 보낸 주소의 쿼리에 개인 값이 있을 수 있다.
 */
@Component
@Slf4j
public class LinkImageFetcher {
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    /** 일반 브라우저 꼴의 고정 글이다. 개인 정보를 넣지 않는다. */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";
    private static final Map<String, String> HEADERS = Map.of(
        "Accept", "image/png, image/jpeg, image/webp, image/gif, text/html;q=0.9",
        "Accept-Encoding", "identity",
        "User-Agent", USER_AGENT);

    private final LinkImageProperties properties;
    private final ArtifactSourceDnsResolver dnsResolver;
    private final LinkImageTransport transport;
    private final List<Cidr> blockedCidrs;
    /** 스티커 받기와 나눠 쓰지 않는 이 받기 전용 실행기다. 동시 4개를 넘으면 받지 못한 것으로 답한다. */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        0,
        4,
        30,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        runnable -> {
            Thread thread = new Thread(runnable, "link-image-fetcher");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());


    @Autowired
    public LinkImageFetcher(LinkImageProperties properties) {
        this(properties, InetAddress::getAllByName, new ArtifactSourceSocketTransport());
    }


    public LinkImageFetcher(LinkImageProperties properties, ArtifactSourceDnsResolver dnsResolver, LinkImageTransport transport) {
        this.properties = properties;
        this.dnsResolver = dnsResolver;
        this.transport = transport;
        this.blockedCidrs = properties.blockedCidrs().stream().map(Cidr::parse).toList();
        if (properties.blockedHosts().isEmpty() && properties.blockedCidrs().isEmpty()) {
            log.warn("링크 사진 받기의 금지 목록이 비어 있다. 홈서버 자신에게 되돌아오는 요청을 막지 못한다");
        }
    }


    /**
     * @param link https 로 시작하는 받을 주소
     * @throws LinkImageRejectedException 받지 못했을 때. 까닭은 {@link LinkImageRejectedException#reason()} 이다
     */
    public LinkImage fetch(URI link) {
        ArtifactSourceCancellation cancellation = new ArtifactSourceCancellation();
        Future<LinkImage> future;
        try {
            future = executor.submit(() -> fetchLink(link, cancellation));
        } catch (RejectedExecutionException ex) {
            throw reject(Reason.FAILED, "busy", host(link), ex);
        }
        try {
            return future.get(properties.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            cancellation.close();
            throw reject(Reason.FAILED, "interrupted", host(link), ex);
        } catch (TimeoutException ex) {
            future.cancel(true);
            cancellation.close();
            throw reject(Reason.FAILED, "timeout", host(link), ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof LinkImageRejectedException rejected) {
                throw rejected;
            }
            throw reject(Reason.FAILED, "error", host(link), ex.getCause());
        }
    }


    private LinkImage fetchLink(URI link, ArtifactSourceCancellation cancellation) throws IOException {
        int[] redirects = {0};
        Fetched first = follow(link, redirects, cancellation);
        String html;
        try (ArtifactSourceResponse response = first.response()) {
            String type = LinkPageParts.mediaType(response.headers().get("content-type"));
            if (LinkPageParts.IMAGE_TYPES.contains(type)) {
                return image(type, response, first.uri());
            }
            if (!"text/html".equals(type)) {
                throw reject(Reason.NO_IMAGE, "not_image", first.uri().getHost(), null);
            }
            html = new String(readUpTo(response.body(), properties.maxPageBytes()), StandardCharsets.UTF_8);
        }
        Optional<URI> preview = LinkPageParts.previewImage(html).flatMap(text -> LinkPageParts.resolve(first.uri(), text));
        if (preview.isEmpty()) {
            throw reject(Reason.NO_IMAGE, "no_preview", first.uri().getHost(), null);
        }
        // 페이지에서 페이지로는 한 번만 간다. 대표 사진 주소가 다시 페이지면 사진이 없는 것이다.
        Fetched second = follow(preview.get(), redirects, cancellation);
        try (ArtifactSourceResponse response = second.response()) {
            String type = LinkPageParts.mediaType(response.headers().get("content-type"));
            if (!LinkPageParts.IMAGE_TYPES.contains(type)) {
                throw reject(Reason.NO_IMAGE, "preview_not_image", second.uri().getHost(), null);
            }
            return image(type, response, second.uri());
        }
    }


    /** 주소 하나를 넘기기를 따라 받는다. 200 응답과 최종 주소를 돌려준다. 넘기기 횟수는 링크 하나의 모든 요청이 함께 센다. */
    private Fetched follow(URI start, int[] redirects, ArtifactSourceCancellation cancellation) throws IOException {
        URI current = start;
        while (true) {
            ArtifactSourceResponse response = request(current, cancellation);
            if (REDIRECTS.contains(response.status())) {
                String location = response.headers().get("location");
                response.close();
                Optional<URI> next = LinkPageParts.resolve(current, location);
                if (next.isEmpty()) {
                    throw reject(Reason.FAILED, "bad_location", current.getHost(), null);
                }
                redirects[0]++;
                if (redirects[0] > properties.maxRedirects()) {
                    throw reject(Reason.BLOCKED, "too_many_redirects", current.getHost(), null);
                }
                current = next.get();
                continue;
            }
            if (response.status() != 200) {
                response.close();
                throw reject(Reason.FAILED, "status_" + response.status(), current.getHost(), null);
            }
            if (response.headers().containsKey("content-encoding")) {
                response.close();
                throw reject(Reason.FAILED, "content_encoding", current.getHost(), null);
            }
            return new Fetched(current, response);
        }
    }


    private ArtifactSourceResponse request(URI uri, ArtifactSourceCancellation cancellation) {
        String host;
        try {
            host = ArtifactSourceFetcher.checkSource(uri);
        } catch (ArtifactSourceRejectedException ex) {
            throw reject(Reason.BLOCKED, ex.reason(), uri == null ? null : uri.getHost(), null);
        }
        if (isBlockedHost(host)) {
            throw reject(Reason.BLOCKED, "blocked_host", host, null);
        }
        List<InetAddress> addresses = publicAddresses(host);
        LinkImageRequest request = new LinkImageRequest(
            uri, HEADERS, properties.connectTimeout(), properties.readTimeout(), properties.maxHeaderBytes());
        IOException last = null;
        for (InetAddress address : addresses) {
            try {
                return transport.get(address, host, request, cancellation);
            } catch (IOException ex) {
                last = ex;
            }
        }
        throw reject(Reason.FAILED, "connect", host, last);
    }


    /** 이름을 풀어 모든 주소가 공개 IP 이고 금지 대역 밖인지 본다. 풀리지 않는 것과 막힌 것은 같은 까닭이다. */
    private List<InetAddress> publicAddresses(String host) {
        InetAddress[] resolved;
        try {
            resolved = dnsResolver.resolve(host);
        } catch (Exception ex) {
            // 풀리지 않는 이름과 내부망으로 풀리는 이름을 같은 까닭으로 낸다. 서버 쪽 DNS 의 내부 이름을 알아내지 못하게 한다.
            throw reject(Reason.BLOCKED, "address", host, null);
        }
        if (resolved == null || resolved.length == 0) {
            throw reject(Reason.BLOCKED, "address", host, null);
        }
        List<InetAddress> addresses = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            if (!ArtifactSourceFetcher.isPublic(address) || blockedCidrs.stream().anyMatch(cidr -> cidr.contains(address))) {
                throw reject(Reason.BLOCKED, "address", host, null);
            }
            addresses.add(address);
        }
        return addresses;
    }


    private boolean isBlockedHost(String host) {
        return properties.blockedHosts().stream().anyMatch(blocked -> host.equals(blocked) || host.endsWith("." + blocked));
    }


    private LinkImage image(String type, ArtifactSourceResponse response, URI uri) throws IOException {
        String length = response.headers().get("content-length");
        if (length != null) {
            long value;
            try {
                value = Long.parseLong(length);
            } catch (NumberFormatException ex) {
                throw reject(Reason.FAILED, "bad_length", uri.getHost(), null);
            }
            if (value > properties.maxImageBytes()) {
                throw reject(Reason.TOO_LARGE, "too_large", uri.getHost(), null);
            }
        }
        byte[] bytes = readUpTo(response.body(), properties.maxImageBytes() + 1);
        if (bytes.length > properties.maxImageBytes()) {
            throw reject(Reason.TOO_LARGE, "too_large", uri.getHost(), null);
        }
        if (!LinkPageParts.matchesImage(type, bytes)) {
            throw reject(Reason.NO_IMAGE, "image_bytes", uri.getHost(), null);
        }
        return new LinkImage(type, bytes);
    }


    /** 많아야 {@code limit} 바이트를 읽는다. 넘는 부분은 읽지 않는다. */
    private static byte[] readUpTo(InputStream body, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() < limit) {
            int read = body.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }


    private static String host(URI uri) {
        return uri == null ? null : uri.getHost();
    }


    private static LinkImageRejectedException reject(Reason reason, String detail, String host, Throwable cause) {
        log.warn("링크 사진을 받지 못했다 reason={} detail={} host={}", reason, detail, host);
        String message = "link image " + reason.name().toLowerCase(Locale.ROOT);
        return cause == null ? new LinkImageRejectedException(reason, message) : new LinkImageRejectedException(reason, message, cause);
    }


    private record Fetched(URI uri, ArtifactSourceResponse response) {
    }

    /** 금지 IP 대역 하나다. */
    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            int slash = text.indexOf('/');
            try {
                byte[] network = InetAddress.getByName(text.substring(0, slash)).getAddress();
                int prefix = Integer.parseInt(text.substring(slash + 1));
                if (prefix > network.length * 8) {
                    throw new IllegalStateException("blocked CIDR prefix is too long");
                }
                return new Cidr(network, prefix);
            } catch (UnknownHostException ex) {
                throw new IllegalStateException("blocked CIDR is invalid", ex);
            }
        }


        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 0x80 >> (bit % 8);
                if ((bytes[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
