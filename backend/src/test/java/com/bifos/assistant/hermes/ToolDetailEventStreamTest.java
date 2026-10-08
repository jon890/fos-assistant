package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.RunEvent;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /** 붙은 커넥터 서버의 도구와 다른 도구가 한 실행에서 함께 돈다. */
    private static final String MIXED_RAW = """
            data: {"event":"tool.started","tool":"web_search","preview":"호출-전-1180 Bearer short-secret"}

            data: {"event":"tool.started","tool":"mcp__demo__list_notes","preview":"{\\"query\\": \\"외부-글-4821\\"}"}

            data: {"event":"tool.completed","tool":"mcp__demo__list_notes","result":"외부-결과-4821"}

            data: {"event":"tool.completed","tool":"web_search","result":"검색-결과-1180"}

            data: {"event":"tool.completed","tool":"mcp__demoother__list","result":"다른-서버-3307"}

            """;

    /**
     * Hermes v0.21.5 가 붙은 커넥터 서버의 도구에 실제로 보내는 모양이다. 모든 사건에 {@code run_id} 와 {@code timestamp} 가 있고, 완료
     * 사건은 {@code result} 가 아니라 비밀값을 가리고 500자로 자른 결과 {@code preview} 를 싣는다. 값은 가짜다.
     */
    private static final String BOUND_TOOL_RAW = """
            data: {"event": "tool.started", "run_id": "run_one", "timestamp": 1790000000.1, "tool": "mcp__demo__list_notes", "preview": "외부-글-4821"}

            data: {"event": "tool.completed", "run_id": "run_one", "timestamp": 1790000000.2, "tool": "mcp__demo__list_notes", "duration": 0.05, "error": false, "preview": "{\\"note\\": \\"외부-결과-4821\\"}"}

            data: {"event": "tool.started", "run_id": "run_one", "timestamp": 1790000000.3, "tool": "terminal", "preview": "ls"}

            data: {"event": "tool.completed", "run_id": "run_one", "timestamp": 1790000000.4, "tool": "terminal", "duration": 0.1, "error": true, "preview": "없다"}

            """;

    private static final String OTHER_TOOL_RAW = """
            data: {"event":"tool.completed","tool":"web_search","result":"Bearer short-secret 검색 결과"}

            """;

    @Test
    @DisplayName("같은 스트림의 도구 시작과 완료에서 UUID 번호표를 유지하고 비밀값은 가린다")
    void preservesUuidLabelsAcrossStartedAndCompletedEvents() throws IOException {
        HttpServer server = startServer(RAW);
        try {
            List<RunEvent> events = read(server, ToolDetailScope.NONE);

            assertThat(events).extracting(RunEvent::detail).containsExactly("[항목 1] [항목 2]", "[항목 2] [항목 1] [가림]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("옛 커넥터 에이전트의 스트림은 두 사건 모두 원문 전체를 가린다")
    void hidesAllDetailsForConnectorStream() throws IOException {
        HttpServer server = startServer(RAW);
        try {
            assertThat(read(server, ToolDetailScope.ALL))
                    .extracting(RunEvent::detail)
                    .containsExactly("[연결 도구 내용 가림]", "[연결 도구 내용 가림]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("붙은 커넥터 호출 전에는 비밀값만 가리고 호출 뒤 일반 도구는 길이만 남긴다")
    void hidesOtherToolDetailsAfterAttachedConnectorCall() throws IOException {
        HttpServer server = startServer(MIXED_RAW);
        try {
            List<RunEvent> events = read(server, ToolDetailScope.prefixes(Set.of("mcp__demo__")));

            assertThat(events)
                    .extracting(RunEvent::detail)
                    .containsExactly(
                            "호출-전-1180 [가림]", "[연결 도구 내용 가림]", "[연결 도구 내용 가림]", "[도구 내용 가림: 10자]", "[도구 내용 가림: 10자]");
            assertThat(events)
                    .extracting(RunEvent::toolName)
                    .containsExactly(
                            "web_search",
                            "mcp__demo__list_notes",
                            "mcp__demo__list_notes",
                            "web_search",
                            "mcp__demoother__list");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("붙은 커넥터 서버의 도구가 실제 모양으로 오면 이름과 시간과 실패는 남기고 내용만 가린다")
    void keepsNameDurationAndFailureOfBoundConnectorToolInRealShape() throws IOException {
        HttpServer server = startServer(BOUND_TOOL_RAW);
        try {
            List<RunEvent> events = read(server, ToolDetailScope.prefixes(Set.of("mcp__demo__")));

            assertThat(events)
                    .extracting(RunEvent::type)
                    .containsExactly("tool.started", "tool.completed", "tool.started", "tool.completed");
            assertThat(events)
                    .extracting(RunEvent::toolName)
                    .containsExactly("mcp__demo__list_notes", "mcp__demo__list_notes", "terminal", "terminal");
            assertThat(events)
                    .extracting(RunEvent::detail)
                    .containsExactly("[연결 도구 내용 가림]", "[연결 도구 내용 가림]", "[도구 내용 가림: 2자]", "[도구 내용 가림: 2자]");
            assertThat(events).extracting(RunEvent::durationMs).containsExactly(null, 50L, null, 100L);
            assertThat(events).extracting(RunEvent::failed).containsExactly(null, false, null, true);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("연결 없는 실행은 일반 도구 내용에 기존 비밀값 가림과 500자 상한을 쓴다")
    void keepsExistingRuleWithoutBindings() throws IOException {
        String raw = "data: {\"event\":\"tool.started\",\"tool\":\"terminal\",\"preview\":\"Bearer tiny "
                + "가".repeat(600) + "\"}\n\n";
        HttpServer server = startServer(raw);
        try {
            assertThat(read(server, ToolDetailScope.NONE).getFirst().detail())
                    .startsWith("[가림] ")
                    .hasSize(500)
                    .endsWith("…");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("자식이나 형제의 커넥터 호출 뒤에는 연결 없는 부모도 목표와 일반 도구를 가린다")
    void usesTreeHistoryAtEventTimeAndDoesNotLeakIntoNextStream() throws IOException {
        String raw = """
                data: {"event":"tool.started","timestamp":1000,"tool":"agent_delegate","preview":"호출 전"}

                data: {"event":"subagent.start","timestamp":1002,"goal":"메일 본문 전달"}

                data: {"event":"tool.completed","timestamp":1003,"tool":"agent_status","result":"메일 본문 반환"}

                """;
        HttpServer server = startServer(raw);
        try {
            ToolDetailScope scope =
                    ToolDetailScope.NONE.withTreeHistory(at -> !at.isBefore(Instant.ofEpochSecond(1001)));
            List<RunEvent> events = read(server, scope);
            assertThat(events).extracting(RunEvent::detail).containsExactly("호출 전", null, "[도구 내용 가림: 8자]");
            assertThat(events.get(1).goal()).isEqualTo("[도구 내용 가림: 8자]");
            assertThat(read(server, ToolDetailScope.NONE))
                    .extracting(RunEvent::detail)
                    .containsExactly("호출 전", null, "메일 본문 반환");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("옛 커넥터 에이전트의 스트림은 커넥터 도구가 아닌 것도 모두 가린다")
    void hidesEveryToolDetailForLegacyConnectorAgent() throws IOException {
        HttpServer server = startServer(MIXED_RAW);
        try {
            assertThat(read(server, ToolDetailScope.ALL))
                    .extracting(RunEvent::detail)
                    .containsOnly("[연결 도구 내용 가림]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("memory_read 사건은 시작의 인자만 남기고 완료의 결과는 남기지 않는다")
    void keepsOnlyArgumentsOfMemoryReadEvents() throws IOException {
        HttpServer server = startServer(MEMORY_READ_RAW);
        try {
            List<RunEvent> events = read(server, ToolDetailScope.NONE);

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
            List<RunEvent> events = read(server, ToolDetailScope.NONE);

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
            assertThat(read(server, ToolDetailScope.NONE))
                    .extracting(RunEvent::detail)
                    .containsExactly("[가림] 검색 결과");
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

    private static List<RunEvent> read(HttpServer server, ToolDetailScope scope) {
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
                scope);
        return events;
    }
}
