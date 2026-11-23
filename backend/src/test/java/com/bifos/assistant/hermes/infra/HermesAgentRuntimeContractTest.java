package com.bifos.assistant.hermes.infra;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import com.bifos.assistant.runtime.application.port.AgentRuntime;
import com.bifos.assistant.runtime.application.port.AgentRuntimeContractTest;
import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP 구현과 사건 스트림을 감싼 Hermes 구현이 실행 port 의 계약을 지키는지 가짜 Hermes 서버로 본다. */
class HermesAgentRuntimeContractTest extends AgentRuntimeContractTest {

    private static final String PROFILE = "contract-profile";

    private static final Map<RunEventKind, String> TERMINAL_NAMES = Map.of(
        RunEventKind.RUN_COMPLETED, "run.completed",
        RunEventKind.RUN_FAILED, "run.failed",
        RunEventKind.RUN_CANCELLED, "run.cancelled",
        RunEventKind.RUN_INTERRUPTED, "run.interrupted");

    private HttpServer server;
    private AgentRuntime runtime;


    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        TERMINAL_NAMES.forEach((kind, name) -> events(
            "run-" + kind.name(),
            "data: {\"event\":\"message.delta\",\"delta\":\"답\"}\n\ndata: {\"event\":\"" + name + "\"}\n\n"));
        events(
            "run-tool",
            "data: {\"event\":\"tool.started\",\"tool\":\"web_search\",\"preview\":\"" + TOOL_DETAIL_MARKER + "\"}\n\n"
                + "data: {\"event\":\"tool.completed\",\"tool\":\"web_search\",\"result\":\"" + TOOL_DETAIL_MARKER
                + "\"}\n\ndata: {\"event\":\"run.completed\"}\n\n");
        server.start();

        HermesProfileKeyStore keys = mock(HermesProfileKeyStore.class);
        when(keys.resolve(PROFILE)).thenReturn("contract-key");
        HermesProperties properties = new HermesProperties(null, null, null, null, null, null, null, null);
        runtime = new HermesAgentRuntime(
            new HttpHermesRunsClient(keys, properties, Duration.ofSeconds(1)),
            new HermesRunEventStream(keys, properties, JsonMapper.builder().build()));
    }


    @AfterEach
    void stop() {
        server.stop(0);
    }


    @Override
    protected AgentRuntime runtime() {
        return runtime;
    }


    @Override
    protected RuntimeTarget target() {
        return new RuntimeTarget(RuntimeKind.HERMES, "http://127.0.0.1:" + server.getAddress().getPort(), PROFILE);
    }


    @Override
    protected String runEndingWith(RunEventKind terminalKind) {
        return "run-" + terminalKind.name();
    }


    @Override
    protected String runWithToolDetail() {
        return "run-tool";
    }


    @Override
    protected String unknownRunId() {
        return "run-unknown";
    }


    @Override
    protected boolean supportsSteer() {
        return true;
    }


    private void events(String runId, String raw) {
        server.createContext("/v1/runs/" + runId + "/events", exchange -> {
            byte[] data = raw.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (var body = exchange.getResponseBody()) {
                body.write(data);
            }
        });
    }
}
