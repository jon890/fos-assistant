package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.application.dto.RunEvent;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.domain.ToolDetailScope;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Hermes v0.23.2 의 사건 모양을 받고, 끝 사건을 실행 조회 쪽에 넘기는지 본다(docs/features/hermes-upgrade/design.md). */
class HermesRunTerminalStreamTest {

    /** v0.23.2 session 대기열 경로의 합성 사건이다. 도구 사건에 tool_call_id 가 붙고 걸린 시간은 0 이다. */
    private static final String V023_EVENTS = String.join("\n",
        "data: {\"event\": \"tool.started\", \"run_id\": \"run-one\", \"tool\": \"web_search\", \"preview\": \"검색\", \"tool_call_id\": \"call-1\"}",
        "",
        "data: {\"event\": \"tool.completed\", \"run_id\": \"run-one\", \"tool\": \"web_search\", \"duration\": 0, \"error\": false, \"tool_call_id\": \"call-1\", \"preview\": \"결과\"}",
        "",
        "data: {\"event\": \"run.queued\", \"run_id\": \"run-one\", \"message\": \"queued\"}",
        "",
        "data: {\"event\": \"run.completed\", \"run_id\": \"run-one\", \"output\": \"답\", \"pending_steer\": \"넣지 못한 글\", \"runtime\": {\"model\": \"served-model\", \"provider\": \"served-provider\"}}",
        "",
        "");

    /** gateway 가 정상 종료로 내려가며 보내는 끝 사건이다. pending_steer 와 runtime 이 없다. */
    private static final String INTERRUPTED_EVENTS = String.join("\n",
        "data: {\"event\": \"run.interrupted\", \"run_id\": \"run-one\", \"error\": \"Gateway shutdown interrupted the run.\"}",
        "",
        "");

    private HttpServer server;


    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }


    @Test
    @DisplayName("v0.23.2 사건은 도구 사건을 지금처럼 넘기고 걸린 시간 0 은 모름으로, run.queued 는 건너뛰며 끝 사건을 보관소에 남긴다")
    void readsV023EventsAndKeepsTerminal() throws IOException {
        server = serve(V023_EVENTS);
        HermesRunTerminals terminals = new HermesRunTerminals();
        List<RunEvent> events = new ArrayList<>();

        stream(terminals).open(baseUrl(), "test-profile", "run-one", events::add, opened -> {
        }, ToolDetailScope.NONE);

        assertThat(events).extracting(RunEvent::type).containsExactly("tool.started", "tool.completed", "run.completed");
        assertThat(events.get(1).durationMs()).isNull();
        HermesRunTerminal terminal = terminals.take("test-profile", "run-one").orElseThrow();
        assertThat(terminal.event()).isEqualTo("run.completed");
        assertThat(terminal.pendingSteer()).isEqualTo("넣지 못한 글");
        assertThat(terminal.pendingSteerPresent()).isTrue();
        assertThat(terminal.servedModel()).isEqualTo("served-model");
        assertThat(terminal.servedProvider()).isEqualTo("served-provider");
        assertThat(terminals.take("test-profile", "run-one")).isEmpty();
    }


    @Test
    @DisplayName("끝난 실행의 보관분을 다시 읽으면 끝 사건만 꺼낸다")
    void readTerminalTakesOnlyTheTerminalEvent() throws IOException {
        server = serve(INTERRUPTED_EVENTS);
        Optional<HermesRunTerminal> terminal = replay().readTerminal(baseUrl(), "test-profile", "run-one");

        assertThat(terminal).hasValueSatisfying(found -> {
            assertThat(found.interrupted()).isTrue();
            assertThat(found.error()).isEqualTo("Gateway shutdown interrupted the run.");
            assertThat(found.pendingSteerPresent()).isFalse();
        });
    }


    @Test
    @DisplayName("보관분이 없는 run(404)은 빈 값이고 예외를 올리지 않는다")
    void readTerminalReturnsEmptyWhenRunIsUnknown() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs/run-one/events", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        assertThat(replay().readTerminal(baseUrl(), "test-profile", "run-one")).isEmpty();
    }


    private HermesRunEventStream stream(HermesRunTerminals terminals) {
        HermesProfileKeyStore keys = mock(HermesProfileKeyStore.class);
        when(keys.resolve("test-profile")).thenReturn("test-key");
        HermesProperties properties = new HermesProperties(null, null, null, null, null, null, null, null);
        return new HermesRunEventStream(keys, properties, JsonMapper.builder().build(), Clock.systemUTC(), terminals);
    }


    private static HermesRunReplay replay() {
        HermesProfileKeyStore keys = mock(HermesProfileKeyStore.class);
        when(keys.resolve("test-profile")).thenReturn("test-key");
        return new HermesRunReplay(
            keys,
            new HermesProperties(null, null, null, null, null, null, null, null),
            JsonMapper.builder().build(),
            Duration.ofSeconds(5));
    }


    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }


    private static HttpServer serve(String raw) throws IOException {
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
}
