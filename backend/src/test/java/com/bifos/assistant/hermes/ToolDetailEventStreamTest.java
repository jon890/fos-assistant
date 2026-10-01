package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.RunEvent;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ToolDetailEventStreamTest {
    private static final String RAW = """
            data: {"event":"tool.started","preview":"12345678-1234-5678-9012-123456789abc abcdef01-1234-5678-9012-123456789abc"}

            data: {"event":"tool.completed","detail":"abcdef01-1234-5678-9012-123456789abc 12345678-1234-5678-9012-123456789abc Bearer short-secret"}

            """;

    @Test
    @DisplayName("같은 스트림의 도구 시작과 완료에서 UUID 번호표를 유지하고 비밀값은 가린다")
    void preservesUuidLabelsAcrossStartedAndCompletedEvents() throws IOException {
        HttpServer server = startServer();
        try {
            List<RunEvent> events = read(server, false);

            assertThat(events).extracting(RunEvent::detail)
                    .containsExactly("[항목 1] [항목 2]", "[항목 2] [항목 1] [가림]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("연결용 스트림은 두 사건 모두 원문 전체를 가린다")
    void hidesAllDetailsForConnectorStream() throws IOException {
        HttpServer server = startServer();
        try {
            assertThat(read(server, true)).extracting(RunEvent::detail)
                    .containsExactly("[연결 도구 내용 가림]", "[연결 도구 내용 가림]");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs/run-one/events", exchange -> {
            byte[] data = RAW.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (var body = exchange.getResponseBody()) {
                body.write(data);
            }
        });
        server.start();
        return server;
    }

    private static List<RunEvent> read(HttpServer server, boolean connectorManaged) {
        HermesProfileKeyStore keys = mock(HermesProfileKeyStore.class);
        when(keys.resolve("test-profile")).thenReturn("test-key");
        HermesProperties properties = new HermesProperties(null, null, null, null, null, null, null, null);
        HermesRunEventStream stream = new HermesRunEventStream(keys, properties, JsonMapper.builder().build());
        List<RunEvent> events = new ArrayList<>();
        stream.open("http://127.0.0.1:" + server.getAddress().getPort(), "test-profile", "run-one",
                events::add, opened -> {}, connectorManaged);
        return events;
    }
}
