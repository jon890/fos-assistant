package com.bifos.assistant.testsupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.ToolDetailScope;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** pinned Hermes 모양의 합성 SSE를 실제 HTTP 파서로 읽는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HermesInterimWire {

    public static String fixture() throws IOException {
        try (var input = HermesInterimWire.class.getResourceAsStream("/hermes/message-interim-v2026.9.24.sse")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void read(String wire, ToolDetailScope scope, Consumer<RunEvent> consumer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs/fixture-run/events", exchange -> {
            byte[] body = wire.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var keys = mock(HermesProfileKeyStore.class);
            when(keys.resolve("fixture")).thenReturn("synthetic-profile-key");
            var properties = new HermesProperties(null, null, null, null, null,
                    Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
            new HermesRunEventStream(keys, properties, JsonMapper.builder().build()).open(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "fixture-run",
                    consumer, stream -> {}, scope);
        } finally {
            server.stop(0);
        }
    }
}
