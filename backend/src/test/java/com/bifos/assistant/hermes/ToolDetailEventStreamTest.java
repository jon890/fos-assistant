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

    private static final String MEMORY_READ_RAW = """
            data: {"event":"tool.started","tool":"mcp__fos_assistant__memory_read","preview":"{\\"id\\": 12}"}

            data: {"event":"tool.completed","tool":"mcp__fos_assistant__memory_read","result":"평문-표식-7391"}

            """;

    private static final String FOLLOW_UP_PROPOSE_RAW = """
            data: {"event":"tool.started","tool":"mcp__fos_assistant__follow_up_propose","preview":"{\\"title\\": \\"제목-표식-5170\\"}"}

            data: {"event":"tool.completed","tool":"mcp__fos_assistant__follow_up_propose","result":"제목-표식-5170 제안됨"}

            """;

    private static final String OTHER_TOOL_RAW = """
            data: {"event":"tool.completed","tool":"web_search","result":"Bearer short-secret 검색 결과"}

            """;

    @Test
    @DisplayName("같은 스트림의 도구 시작과 완료에서 UUID 번호표를 유지하고 비밀값은 가린다")
    void preservesUuidLabelsAcrossStartedAndCompletedEvents() throws IOException {
        HttpServer server = startServer(RAW);
        try {
            List<RunEvent> events = read(server, false);

            assertThat(events).extracting(RunEvent::detail).containsExactly("[항목 1] [항목 2]", "[항목 2] [항목 1] [가림]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("연결용 스트림은 두 사건 모두 원문 전체를 가린다")
    void hidesAllDetailsForConnectorStream() throws IOException {
        HttpServer server = startServer(RAW);
        try {
            assertThat(read(server, true))
                    .extracting(RunEvent::detail)
                    .containsExactly("[연결 도구 내용 가림]", "[연결 도구 내용 가림]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("memory_read 사건은 시작의 인자만 남기고 완료의 결과는 남기지 않는다")
    void keepsOnlyArgumentsOfMemoryReadEvents() throws IOException {
        HttpServer server = startServer(MEMORY_READ_RAW);
        try {
            List<RunEvent> events = read(server, false);

            // 가리는 쪽이 JSON 인자를 다시 직렬화해 공백이 빠진다
            assertThat(events).extracting(RunEvent::detail).containsExactly("{\"id\":12}", null);
            assertThat(events).extracting(RunEvent::toolName).containsOnly("mcp__fos_assistant__memory_read");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("follow_up_propose 사건은 시작과 완료 모두 내용을 남기지 않는다")
    void keepsNoDetailOfFollowUpProposeEvents() throws IOException {
        HttpServer server = startServer(FOLLOW_UP_PROPOSE_RAW);
        try {
            List<RunEvent> events = read(server, false);

            assertThat(events).extracting(RunEvent::detail).containsExactly(null, null);
            assertThat(events).extracting(RunEvent::toolName).containsOnly("mcp__fos_assistant__follow_up_propose");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("다른 도구의 완료 사건은 결과를 가려 남긴다")
    void keepsRedactedResultOfOtherToolCompletedEvent() throws IOException {
        HttpServer server = startServer(OTHER_TOOL_RAW);
        try {
            assertThat(read(server, false)).extracting(RunEvent::detail).containsExactly("[가림] 검색 결과");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String raw) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs/run-one/events", exchange -> {
            byte[] data = raw.getBytes(StandardCharsets.UTF_8);
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
        HermesRunEventStream stream =
                new HermesRunEventStream(keys, properties, JsonMapper.builder().build());
        List<RunEvent> events = new ArrayList<>();
        stream.open(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-profile",
                "run-one",
                events::add,
                opened -> {},
                connectorManaged);
        return events;
    }
}
