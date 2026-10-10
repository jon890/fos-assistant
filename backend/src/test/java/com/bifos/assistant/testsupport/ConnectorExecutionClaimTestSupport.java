package com.bifos.assistant.testsupport;

import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 커넥터 HTTP 대역과 세 검사가 공유하는 Spring 컨텍스트다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestDoubles.class)
@ExtendWith(IntegrationTestIsolation.class)
@MockitoBean(
        types = {
            HermesRunEventStream.class,
            HermesToolsetClient.class,
            HermesSkillClient.class,
            HermesModelClient.class,
            HermesDashboardClient.class
        })
@ResourceLock("connector-execution-catalog")
public abstract class ConnectorExecutionClaimTestSupport {
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final Instant NOW = Instant.parse("2026-10-10T00:00:02.123456Z");
    protected static final String TOKEN = "claim-test-secret";
    protected static final String DECLARATION = "[{\"arg\":\"account_seq\",\"field\":\"account\"}]";
    protected static final String CATALOG = """
            [{"id":"demo-financial","title":"검사용 금융","schema":2,
              "fields":[{"key":"account","env":"DEMO_ACCOUNT","secret":false}],
              "verify":{"tool":"prepare"},"mcp_server":"demo",
              "tools":{"prepare":{"title":"조회","risk":"READ","approval":"none"},
                       "place_order":{"title":"주문","risk":"FINANCIAL","approval":"always"}},
              "execution_guard":{"protocol":"approval-claim-v1","prepare_tool":"prepare",
                "scope_fields":[{"arg":"account_seq","field":"account"}],
                "operations":{"place_order":"CREATE"}}}]
            """;
    protected static final AtomicReference<String> CATALOG_BODY = new AtomicReference<>(CATALOG);
    protected static final AtomicReference<Barrier> BARRIER = new AtomicReference<>();
    protected static final AtomicInteger CATALOG_REQUESTS = new AtomicInteger();
    protected static final AtomicBoolean FAIL_CATALOG = new AtomicBoolean();
    protected static final AtomicBoolean CONFIGURED = new AtomicBoolean(true);
    protected static final HttpServer REMOTE = server();

    @Autowired
    protected TestClock clock;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add(
                "hermes.dashboard-base-url",
                () -> "http://127.0.0.1:" + REMOTE.getAddress().getPort());
        registry.add("hermes.dashboard-token", () -> TOKEN);
        registry.add("hermes.read-timeout", () -> "5s");
        registry.add("assistant.attachment.root", () -> System.getProperty("java.io.tmpdir"));
    }

    @BeforeEach
    void setUpClaim() {
        clock.set(NOW);
        CATALOG_BODY.set(CATALOG);
        FAIL_CATALOG.set(false);
        CONFIGURED.set(true);
        BARRIER.set(null);
    }

    @AfterEach
    void releaseBarrier() {
        Barrier active = BARRIER.getAndSet(null);
        if (active != null) {
            active.release.countDown();
        }
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", ConnectorExecutionClaimTestSupport::respond);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().getPath();
        byte[] request = exchange.getRequestBody().readAllBytes();
        JsonNode body = request.length > 0 ? JSON.readTree(request) : null;
        String response;
        if ("/api/connectors/catalog".equals(uri)) {
            CATALOG_REQUESTS.incrementAndGet();
            Barrier held = BARRIER.getAndSet(null);
            if (held != null) {
                held.entered.countDown();
                try {
                    held.release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (FAIL_CATALOG.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            response = CATALOG_BODY.get();
        } else if ("/api/connectors".equals(uri)) {
            if ("PUT".equals(exchange.getRequestMethod())) {
                response = JSON.writeValueAsString(Map.of(
                        "profile",
                        body.get("profile").stringValue(),
                        "plugin",
                        "demo-financial",
                        "enabled",
                        body.get("enabled").booleanValue(),
                        "restart_required",
                        false));
            } else {
                String profile = URLDecoder.decode(
                        exchange.getRequestURI().getQuery().substring("profile=".length()), StandardCharsets.UTF_8);
                response = JSON.writeValueAsString(
                        Map.of("profile", profile, "policy_hook", true, "connectors", new Object[] {
                            Map.of(
                                    "plugin",
                                    "demo-financial",
                                    "enabled",
                                    true,
                                    "configured",
                                    CONFIGURED.get(),
                                    "mode",
                                    "bind")
                        }));
            }
        } else if ("/api/connector-vault".equals(uri)) {
            response = "{\"ok\":true,\"changed\":true}";
        } else if ("/api/connectors/demo-financial/call".equals(uri)) {
            response = "{\"ok\":true,\"result\":{\"verified\":true}}";
        } else if ("/api/mcp/servers/demo/test".equals(uri)) {
            response = "{\"ok\":true,\"tools\":[{\"name\":\"prepare\"},{\"name\":\"place_order\"}]}";
        } else {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            throw new AssertionError("검사에서 허용하지 않은 외부 요청: " + uri);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    public static final class Barrier {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
    }
}
