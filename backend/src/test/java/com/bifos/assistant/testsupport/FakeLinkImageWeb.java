package com.bifos.assistant.testsupport;

import com.bifos.assistant.chat.artifact.infra.ArtifactSourceCancellation;
import com.bifos.assistant.chat.artifact.infra.ArtifactSourceResponse;
import com.bifos.assistant.chat.artifact.infra.ArtifactSourceSocketTransport;
import com.bifos.assistant.chat.artifact.infra.LinkImageRequest;
import com.bifos.assistant.chat.artifact.infra.LinkImageTransport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 링크 사진 받기의 바깥 웹 대역이다. 이름은 늘 공개 IP 하나로 풀고, 주소마다 정해 둔 응답을 돌려준다. 정하지 않은 주소는 연결 실패다.
 *
 * <p>통합 테스트가 Spring 컨텍스트 하나를 같이 쓰므로 {@link IntegrationTestIsolation} 이 테스트마다 비운다.
 */
public class FakeLinkImageWeb implements LinkImageTransport {
    private final Map<String, byte[]> pages = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();


    /** 주소 하나에 사진을 둔다. */
    public void image(String url, String contentType, byte[] body) {
        pages.put(key(URI.create(url)), response(200, Map.of("Content-Type", contentType), body));
    }


    /** 주소 하나에 웹 페이지를 둔다. */
    public void html(String url, String body) {
        pages.put(key(URI.create(url)), response(200, Map.of("Content-Type", "text/html"), body.getBytes(StandardCharsets.UTF_8)));
    }


    /** 주소 하나에 상태만 있는 답을 둔다. */
    public void status(String url, int status) {
        pages.put(key(URI.create(url)), response(status, Map.of("Content-Type", "text/html"), new byte[0]));
    }


    /** 지금까지 요청한 주소(호스트, 경로, 쿼리)다. */
    public List<String> requested() {
        return List.copyOf(requested);
    }


    public InetAddress[] resolve(String host) throws IOException {
        return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte)184, (byte)216, 34})};
    }


    @Override
    public ArtifactSourceResponse get(InetAddress address, String host, LinkImageRequest request, ArtifactSourceCancellation cancellation)
        throws IOException {
        String key = key(request.source());
        requested.add(key);
        byte[] raw = pages.get(key);
        if (raw == null) {
            throw new IOException("connection refused");
        }
        return ArtifactSourceSocketTransport.parseAnyStatus(new Socket(), new ByteArrayInputStream(raw), request.maxHeaderBytes());
    }


    public void reset() {
        pages.clear();
        requested.clear();
    }


    private static String key(URI uri) {
        return uri.getHost() + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }


    private static byte[] response(int status, Map<String, String> headers, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        out.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body);
        return out.toByteArray();
    }
}
