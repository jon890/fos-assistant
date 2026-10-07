package com.bifos.assistant.browser.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.browser.domain.CdpConnection;
import com.bifos.assistant.browser.domain.CdpEvent;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WebSocketCdpConnectorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TARGET = "ABCDEF0123456789";

    private FakeCdpServer server;
    private WebSocketCdpConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeCdpServer();
        connector = new WebSocketCdpConnector(Duration.ofMillis(300), 1_000_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        connector.close();
        server.close();
    }

    @Test
    @DisplayName("탭의 devtools 경로로 붙고 명령 응답을 번호로 짝지어 돌려준다")
    void pairsResponsesById() throws Exception {
        try (CdpConnection connection = connect(event -> {}, () -> {})) {
            CompletableFuture<JsonNode> first = connection.send("Page.enable", Map.of());
            CompletableFuture<JsonNode> second = connection.send("Page.reload", Map.of("ignoreCache", true));
            JsonNode a = server.nextMessage();
            JsonNode b = server.nextMessage();

            server.sendText("{\"id\":" + b.path("id").asLong() + ",\"result\":{\"which\":\"second\"}}");
            server.sendText("{\"id\":" + a.path("id").asLong() + ",\"result\":{\"which\":\"first\"}}");

            assertThat(server.requestLine()).isEqualTo("GET /devtools/page/" + TARGET + " HTTP/1.1");
            assertThat(server.headers()).noneMatch(line -> line.toLowerCase().startsWith("origin:"));
            assertThat(a.path("method").asString()).isEqualTo("Page.enable");
            assertThat(b.path("params").path("ignoreCache").asBoolean()).isTrue();
            assertThat(first.get(2, TimeUnit.SECONDS).path("which").asString()).isEqualTo("first");
            assertThat(second.get(2, TimeUnit.SECONDS).path("which").asString()).isEqualTo("second");
        }
    }

    @Test
    @DisplayName("CDP 가 오류로 답하면 그 명령만 실패한다")
    void failsCommandOnErrorResponse() throws Exception {
        try (CdpConnection connection = connect(event -> {}, () -> {})) {
            CompletableFuture<JsonNode> result = connection.send("Page.navigate", Map.of("url", "https://example.com"));
            JsonNode sent = server.nextMessage();

            server.sendText("{\"id\":" + sent.path("id").asLong() + ",\"error\":{\"code\":-32000,\"message\":\"x\"}}");

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasMessageNotContaining("example.com");
        }
    }

    @Test
    @DisplayName("번호 없는 메시지는 사건으로 넘기고 조각난 큰 메시지는 모아서 한 사건으로 읽는다")
    void deliversEventsAndJoinsFragments() throws Exception {
        BlockingQueue<CdpEvent> events = new LinkedBlockingQueue<>();
        try (CdpConnection ignored = connect(events::add, () -> {})) {
            server.sendText("{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":1}}");
            String data = "a".repeat(400_000);
            String big = "{\"method\":\"Page.screencastFrame\",\"params\":{\"data\":\"" + data + "\",\"sessionId\":7}}";
            server.sendFragmented(big, 3);

            CdpEvent first = events.poll(2, TimeUnit.SECONDS);
            CdpEvent second = events.poll(2, TimeUnit.SECONDS);

            assertThat(first.method()).isEqualTo("Page.loadEventFired");
            assertThat(second.method()).isEqualTo("Page.screencastFrame");
            assertThat(second.params().path("data").asString()).hasSize(data.length());
            assertThat(second.params().path("sessionId").asInt()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("정한 시간 안에 답이 없으면 그 명령을 시간 초과로 끝낸다")
    void timesOutUnansweredCommand() throws Exception {
        try (CdpConnection connection = connect(event -> {}, () -> {})) {
            CompletableFuture<JsonNode> result = connection.send("Page.enable", Map.of());
            server.nextMessage();

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        }
    }

    @Test
    @DisplayName("상대가 끊으면 기다리던 명령을 실패로 끝내고 닫힘을 한 번 알린다")
    void failsPendingAndNotifiesOnDisconnect() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        try (CdpConnection connection = connect(event -> {}, closed::countDown)) {
            CompletableFuture<JsonNode> result = connection.send("Page.enable", Map.of());
            server.nextMessage();

            server.dropConnection();

            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(connection.send("Page.enable", Map.of())).isCompletedExceptionally();
        }
    }

    @Test
    @DisplayName("상한을 넘는 메시지가 오면 연결을 끊고 기다리던 명령을 실패로 끝내며 닫힘을 알린다")
    void closesOnOversizedMessage() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        try (WebSocketCdpConnector small = new WebSocketCdpConnector(Duration.ofSeconds(5), 1_000)) {
            CompletableFuture<Void> accepted = server.acceptAsync();
            CdpConnection connection = small.connect(server.address(), TARGET, event -> {}, closed::countDown);
            accepted.get(2, TimeUnit.SECONDS);
            CompletableFuture<JsonNode> result = connection.send("Page.enable", Map.of());
            server.nextMessage();

            server.sendFragmented(
                    "{\"method\":\"Page.screencastFrame\",\"params\":{\"data\":\"" + "a".repeat(2_000) + "\"}}", 4);

            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(server.awaitDisconnected()).isTrue();
        }
    }

    @Test
    @DisplayName("close 로 닫으면 기다리던 명령을 실패로 끝내고 닫힘은 알리지 않는다")
    void closeFailsPendingWithoutNotifying() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        CdpConnection connection = connect(event -> {}, closed::countDown);
        CompletableFuture<JsonNode> result = connection.send("Page.enable", Map.of());
        server.nextMessage();

        connection.close();

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(server.awaitDisconnected()).isTrue();
        assertThat(closed.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    @DisplayName("두 스레드가 동시에 여러 번 보내도 응답이 모두 제 명령과 짝지어진다")
    void pairsConcurrentSends() throws Exception {
        int perThread = 50;
        try (CdpConnection connection = connect(event -> {}, () -> {})) {
            Thread echo = new Thread(() -> {
                try {
                    for (int i = 0; i < perThread * 2; i++) {
                        JsonNode sent = server.nextMessage();
                        server.sendText("{\"id\":" + sent.path("id").asLong() + ",\"result\":{\"n\":"
                                + sent.path("params").path("n").asInt() + "}}");
                    }
                } catch (InterruptedException | IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            echo.setDaemon(true);
            echo.start();
            List<List<CompletableFuture<JsonNode>>> results = new ArrayList<>();
            List<Thread> senders = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                List<CompletableFuture<JsonNode>> mine = new ArrayList<>();
                results.add(mine);
                int base = t * 1_000;
                Thread sender = new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        mine.add(connection.send("Runtime.evaluate", Map.of("n", base + i)));
                    }
                });
                senders.add(sender);
                sender.start();
            }
            for (Thread sender : senders) {
                sender.join(2_000);
            }

            for (int t = 0; t < 2; t++) {
                for (int i = 0; i < perThread; i++) {
                    assertThat(results.get(t)
                                    .get(i)
                                    .get(2, TimeUnit.SECONDS)
                                    .path("n")
                                    .asInt())
                            .isEqualTo(t * 1_000 + i);
                }
            }
        }
    }

    @Test
    @DisplayName("사건 처리기가 막혀 있어도 명령 응답은 오고, 처리기는 읽는 스레드가 아닌 한 스레드에서 차례대로 불린다")
    void answersCommandsWhileEventHandlerBlocks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<String> handled = new LinkedBlockingQueue<>();
        AtomicReference<String> firstThread = new AtomicReference<>();
        AtomicInteger otherThreads = new AtomicInteger();
        Consumer<CdpEvent> events = event -> {
            String name = Thread.currentThread().getName();
            if (!firstThread.compareAndSet(null, name) && !firstThread.get().equals(name)) {
                otherThreads.incrementAndGet();
            }
            handled.add(event.method());
            if (event.method().equals("Page.first")) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (CdpConnection connection = connect(events, () -> {})) {
            server.sendText("{\"method\":\"Page.first\"}");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            server.sendText("{\"method\":\"Page.second\"}");
            CompletableFuture<JsonNode> result = connection.send("Page.enable", Map.of());
            JsonNode sent = server.nextMessage();

            server.sendText("{\"id\":" + sent.path("id").asLong() + ",\"result\":{\"ok\":true}}");

            assertThat(result.get(2, TimeUnit.SECONDS).path("ok").asBoolean()).isTrue();
            release.countDown();
            assertThat(handled.poll(2, TimeUnit.SECONDS)).isEqualTo("Page.first");
            assertThat(handled.poll(2, TimeUnit.SECONDS)).isEqualTo("Page.second");
            assertThat(firstThread.get()).startsWith("cdp-events-");
            assertThat(otherThreads.get()).isZero();
        }
    }

    @Test
    @DisplayName("붙는 중에 끊기면 늦게 열린 소켓을 닫고 사건과 닫힘 알림을 부르지 않는다")
    void abortsLateSocketAfterInterruptedConnect() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BlockingQueue<CdpEvent> events = new LinkedBlockingQueue<>();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.acceptAsync(gate);
        Thread caller = new Thread(() -> {
            try {
                connector.connect(server.address(), TARGET, events::add, closed::countDown);
            } catch (RuntimeException ex) {
                failure.set(ex);
            }
        });
        caller.start();
        assertThat(server.awaitConnected()).isTrue();

        caller.interrupt();
        caller.join(2_000);
        gate.countDown();

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        assertThat(server.awaitDisconnected()).isTrue();
        assertThat(events.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(closed.getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("대상 번호가 영문자와 숫자가 아니면 붙지 않고 거절한다")
    void rejectsMalformedTargetId() {
        URI cdp = server.address();
        for (String id : new String[] {"../browser", "a/b", "", null}) {
            assertThatThrownBy(() -> connector.connect(cdp, id, event -> {}, () -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private CdpConnection connect(Consumer<CdpEvent> events, Runnable closed) throws Exception {
        CompletableFuture<Void> accepted = server.acceptAsync();
        CdpConnection connection = connector.connect(server.address(), TARGET, events, closed);
        accepted.get(2, TimeUnit.SECONDS);
        return connection;
    }

    /** 핸드셰이크와 텍스트 프레임만 아는 가짜 CDP WebSocket 서버다. 연결 하나만 받는다. */
    static final class FakeCdpServer implements AutoCloseable {

        private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private final ServerSocket server;
        private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final List<String> headers = new ArrayList<>();
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private volatile Socket socket;
        private volatile Thread reader;

        FakeCdpServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        }

        URI address() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort());
        }

        CompletableFuture<Void> acceptAsync() {
            return acceptAsync(new CountDownLatch(0));
        }

        /** 연결을 받은 뒤 {@code gate} 가 열릴 때까지 핸드셰이크 응답을 미룬다. 응답한 뒤에는 사건 하나를 보낸다. */
        CompletableFuture<Void> acceptAsync(CountDownLatch gate) {
            CompletableFuture<Void> accepted = new CompletableFuture<>();
            boolean delayed = gate.getCount() > 0;
            Thread thread = new Thread(() -> {
                try {
                    socket = server.accept();
                    connected.countDown();
                    gate.await(5, TimeUnit.SECONDS);
                    handshake();
                    accepted.complete(null);
                    if (delayed) {
                        sendText("{\"method\":\"Page.late\"}");
                    }
                    readFrames();
                } catch (IOException | RuntimeException | InterruptedException ex) {
                    accepted.completeExceptionally(ex);
                } finally {
                    disconnected.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
            reader = thread;
            return accepted;
        }

        String requestLine() {
            return headers.get(0);
        }

        List<String> headers() {
            return headers;
        }

        boolean awaitConnected() throws InterruptedException {
            return connected.await(2, TimeUnit.SECONDS);
        }

        /** 상대가 연결을 닫아 읽기를 마칠 때까지 기다린다. */
        boolean awaitDisconnected() throws InterruptedException {
            return disconnected.await(2, TimeUnit.SECONDS);
        }

        JsonNode nextMessage() throws InterruptedException {
            JsonNode message = received.poll(2, TimeUnit.SECONDS);
            assertThat(message).as("client message").isNotNull();
            return message;
        }

        synchronized void sendText(String text) throws IOException {
            writeFrame(true, 0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void sendFragmented(String text, int parts) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            int size = bytes.length / parts;
            for (int i = 0; i < parts; i++) {
                int from = i * size;
                int to = i == parts - 1 ? bytes.length : from + size;
                byte[] part = Arrays.copyOfRange(bytes, from, to);
                writeFrame(i == parts - 1, i == 0 ? 0x1 : 0x0, part);
            }
        }

        void dropConnection() throws IOException {
            socket.close();
        }

        @Override
        public void close() throws Exception {
            if (socket != null) {
                socket.close();
            }
            server.close();
            if (reader != null) {
                reader.join(2_000);
            }
        }

        private void handshake() throws IOException {
            InputStream in = socket.getInputStream();
            String key = null;
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                headers.add(line);
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    key = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            String accept;
            try {
                accept = Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-1")
                                .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void readFrames() throws IOException {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            try {
                while (true) {
                    int first = in.readUnsignedByte();
                    int second = in.readUnsignedByte();
                    long length = second & 0x7F;
                    if (length == 126) {
                        length = in.readUnsignedShort();
                    } else if (length == 127) {
                        length = in.readLong();
                    }
                    byte[] mask = new byte[4];
                    if ((second & 0x80) != 0) {
                        in.readFully(mask);
                    }
                    byte[] payload = new byte[(int) length];
                    in.readFully(payload);
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                    int opcode = first & 0x0F;
                    if (opcode == 0x8) {
                        return;
                    }
                    if (opcode == 0x1 || opcode == 0x0) {
                        message.write(payload);
                        if ((first & 0x80) != 0) {
                            received.add(JSON.readTree(message.toString(StandardCharsets.UTF_8)));
                            message.reset();
                        }
                    }
                }
            } catch (IOException ex) {
                // 연결이 닫히면 읽기를 마친다
            }
        }

        private void writeFrame(boolean fin, int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write((fin ? 0x80 : 0) | opcode);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else if (payload.length < 65_536) {
                frame.write(126);
                frame.write(payload.length >>> 8);
                frame.write(payload.length & 0xFF);
            } else {
                frame.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame.write((int) (((long) payload.length >>> shift) & 0xFF));
                }
            }
            frame.write(payload);
            OutputStream out = socket.getOutputStream();
            out.write(frame.toByteArray());
            out.flush();
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
                if (b != '\r') {
                    line.write(b);
                }
            }
            return line.toString(StandardCharsets.US_ASCII);
        }
    }
}
