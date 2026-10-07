package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.CdpProbe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * CDP 의 {@code GET /json/version} 을 {@code Host: localhost} 로 부른다.
 *
 * <p>Chrome 의 CDP 창구는 {@code Host} 가 IP 나 {@code localhost} 가 아니면 거절한다. JDK 의 HTTP 클라이언트는 {@code Host} 를
 * 바꾸지 못하게 막으므로 소켓에 요청 한 줄을 직접 쓴다. 답의 상태 줄만 읽고 본문은 읽지 않는다.
 */
@Component
public class HttpCdpProbe implements CdpProbe {

    private static final int TIMEOUT_MILLIS = 2_000;
    private static final int DEFAULT_PORT = 80;
    private static final String REQUEST =
            "GET /json/version HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\nConnection: close\r\n\r\n";

    @Override
    public boolean ready(URI address) {
        int port = address.getPort() < 0 ? DEFAULT_PORT : address.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.getHost(), port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(REQUEST.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String status = in.readLine();
            return status != null && status.matches("^HTTP/1\\.[01] 200( .*)?$");
        } catch (IOException ex) {
            return false;
        }
    }
}
