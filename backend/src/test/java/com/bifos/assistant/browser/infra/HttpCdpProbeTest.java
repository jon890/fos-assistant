package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpCdpProbeTest {

    private final HttpCdpProbe probe = new HttpCdpProbe();

    @Test
    @DisplayName("Host 를 localhost 로 두고 /json/version 을 불러 200 이면 준비된 것으로 본다")
    void readsVersionWithLocalhostHost() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<List<String>> received = answerOnce(server, "HTTP/1.1 200 OK");

            boolean ready = probe.ready(URI.create("http://127.0.0.1:" + server.getLocalPort()));

            assertThat(ready).isTrue();
            assertThat(received.get()).contains("GET /json/version HTTP/1.1", "Host: localhost");
        }
    }

    @Test
    @DisplayName("200 이 아니거나 닿지 못하면 준비되지 않은 것으로 본다")
    void reportsNotReadyOnErrorOrUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            answerOnce(server, "HTTP/1.1 500 Internal Server Error");
            assertThat(probe.ready(URI.create("http://127.0.0.1:" + server.getLocalPort())))
                    .isFalse();
            closedPort = server.getLocalPort();
        }
        assertThat(probe.ready(URI.create("http://127.0.0.1:" + closedPort))).isFalse();
    }

    private static CompletableFuture<List<String>> answerOnce(ServerSocket server, String status) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                List<String> lines = new ArrayList<>();
                for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    lines.add(line);
                }
                OutputStream out = socket.getOutputStream();
                out.write((status + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return lines;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }
}
