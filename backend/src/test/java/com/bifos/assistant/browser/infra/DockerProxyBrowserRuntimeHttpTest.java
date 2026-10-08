package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.domain.RuntimeContainer;
import com.bifos.assistant.shared.config.LiveProperties;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DockerProxyBrowserRuntimeHttpTest {

    private static final String KEY = "0123456789abcdef".repeat(4);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("운영 HTTP 생성자는 Docker proxy 에 고정 길이 요청을 보내고 응답을 읽는다")
    void sendsFixedLengthRequestsToProxy() throws Exception {
        try (ProxyServer proxy = new ProxyServer(6)) {
            DockerProxyBrowserRuntime runtime = new DockerProxyBrowserRuntime(
                    LiveProperties.fixed(BrowserProperties.class, properties("http://127.0.0.1:" + proxy.port())));

            assertThat(runtime.create(KEY)).isEqualTo("created");
            runtime.start("created");
            runtime.stop("created");
            runtime.remove("created");
            assertThat(runtime.cdpAddress("created").map(Object::toString)).contains("http://192.0.2.10:9999");
            assertThat(runtime.list()).containsExactly(new RuntimeContainer("listed", KEY, true));

            List<Request> requests = proxy.requests();
            assertThat(requests)
                    .extracting(Request::requestLine)
                    .containsExactly(
                            "POST /containers/create HTTP/1.1",
                            "POST /containers/created/start HTTP/1.1",
                            "POST /containers/created/stop?t=10 HTTP/1.1",
                            "DELETE /containers/created?force=1 HTTP/1.1",
                            "GET /containers/created/json HTTP/1.1",
                            "GET /containers/json?all=1&filters=%7B%22label%22%3A%5B%22fos-browser%3D1%22%5D%7D HTTP/1.1");
            assertThat(requests.subList(0, 4)).allSatisfy(request -> {
                assertThat(request.header("transfer-encoding")).isNull();
                assertThat(request.header("content-length")).isNotNull();
            });
            assertThat(requests.get(0).body().length)
                    .isEqualTo(Integer.parseInt(requests.get(0).header("content-length")));
            JsonNode body = MAPPER.readTree(requests.get(0).body());
            assertThat(body.path("Image").asString()).isEqualTo("example/browser:test");
            assertThat(body.path("Labels").path("fos-browser-user").asString()).isEqualTo(KEY);
            assertThat(body.path("HostConfig").path("Binds").get(0).asString())
                    .isEqualTo("/example/브라우저-profiles/" + KEY + ":/example/profile:rw");
            assertThat(requests.subList(1, 4))
                    .allSatisfy(request ->
                            assertThat(request.header("content-length")).isEqualTo("0"));
            assertThat(requests.subList(4, 6)).allSatisfy(request -> {
                assertThat(request.header("transfer-encoding")).isNull();
                assertThat(request.header("content-length")).isNull();
            });
        }
    }

    private static BrowserProperties properties(String proxyUrl) {
        return new BrowserProperties(
                true,
                proxyUrl,
                "example/browser:test",
                "example-browser",
                9999,
                "build/unused",
                "/example/브라우저-profiles/",
                "/example/profile",
                1024,
                1.5,
                256,
                128,
                2,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30));
    }

    private record Request(String requestLine, Map<String, String> headers, byte[] body) {

        String header(String name) {
            return headers.get(name);
        }
    }

    private static final class ProxyServer implements AutoCloseable {

        private final ServerSocket server;
        private final CompletableFuture<List<Request>> received;

        ProxyServer(int requestCount) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            received = CompletableFuture.supplyAsync(() -> serve(requestCount));
        }

        int port() {
            return server.getLocalPort();
        }

        List<Request> requests() throws Exception {
            return received.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }

        private List<Request> serve(int requestCount) {
            try {
                List<Request> requests = new ArrayList<>();
                for (int index = 0; index < requestCount; index++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5_000);
                        Request request = readRequest(socket);
                        requests.add(request);
                        writeResponse(socket, request, index);
                    }
                }
                return requests;
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private static Request readRequest(Socket socket) throws IOException {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(input);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
                int colon = line.indexOf(':');
                headers.put(
                        line.substring(0, colon).toLowerCase(),
                        line.substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            return new Request(requestLine, headers, input.readNBytes(length));
        }

        private static String readLine(BufferedInputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int value = input.read(); value != '\n'; value = input.read()) {
                if (value == -1) {
                    throw new IOException("unexpected end of HTTP request");
                }
                if (value != '\r') {
                    line.write(value);
                }
            }
            return line.toString(StandardCharsets.US_ASCII);
        }

        private static void writeResponse(Socket socket, Request request, int index) throws IOException {
            boolean needsLength = request.requestLine().startsWith("POST")
                    || request.requestLine().startsWith("DELETE");
            boolean rejected = request.header("transfer-encoding") != null
                    || (needsLength && request.header("content-length") == null);
            String body = switch (index) {
                case 0 -> "{\"Id\":\"created\"}";
                case 4 -> "{\"NetworkSettings\":{\"Networks\":{\"example-browser\":{\"IPAddress\":\"192.0.2.10\"}}}}";
                case 5 ->
                    "[{\"Id\":\"listed\",\"State\":\"running\",\"Labels\":{\"fos-browser-user\":\"" + KEY + "\"}}]";
                default -> "";
            };
            int status = rejected ? 403 : (index == 0 ? 201 : (index >= 4 ? 200 : 204));
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + status + (rejected ? " Forbidden" : " OK") + "\r\nContent-Length: "
                            + body.getBytes(StandardCharsets.UTF_8).length
                            + "\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
                            + body)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
