package com.bifos.assistant.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 받지 않는 메서드로 온 요청을 405 로 돌려주고 ERROR 로 남기지 않는지 실제 서버로 본다.
 *
 * <p>로그인 판정 경로는 인증 없이 닿고 POST 만 받는다. 그래서 보안 필터를 지나 DispatcherServlet 이
 * 메서드를 거절하는 자리까지 간다. 인증이 필요한 경로는 토큰 없는 요청을 보안 필터가 먼저 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MethodNotAllowedTest {

    private static final String POST_ONLY = "/api/v1/signin/allowed";

    @LocalServerPort int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        handlerLogger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        handlerLogger().detachAppender(logs);
    }

    @Test
    @DisplayName("POST 만 받는 경로에 온 HEAD 는 405 와 Allow 를 받고 ERROR 로 남지 않는다")
    void headToPostOnlyPathGets405AndAllowAndIsNotLoggedAsError() throws Exception {
        HttpResponse<String> response = send("HEAD", POST_ONLY);

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("POST"));
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("HEAD"));
    }

    @Test
    @DisplayName("받지 않는 메서드의 응답 본문은 다른 오류와 같은 모양이다")
    void bodyForUnacceptedMethodHasSameShapeAsOtherErrors() throws Exception {
        HttpResponse<String> response = send("GET", POST_ONLY);

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("POST"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
        assertThat(body.path("message").asString()).contains("GET");
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    @Test
    @DisplayName("GET 경로에 온 HEAD 는 Spring 이 GET 으로 처리해 405 가 아니다")
    void headToGetPathIsHandledAsGetBySpringAndIsNot405() throws Exception {
        // 인증 없이 닿는 GET 경로다. @GetMapping 은 HEAD 도 받으므로 메서드 거절에 이르지 않는다.
        HttpResponse<String> response = send("HEAD", "/api/v1/me");

        assertThat(response.statusCode()).isNotEqualTo(405);
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Logger handlerLogger() {
        return (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    }
}
