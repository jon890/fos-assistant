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
    void 준비한다() {
        logs = new ListAppender<>();
        logs.start();
        handlerLogger().addAppender(logs);
    }

    @AfterEach
    void 정리한다() {
        handlerLogger().detachAppender(logs);
    }

    @Test
    void POST_만_받는_경로에_온_HEAD_는_405_와_Allow_를_받고_ERROR_로_남지_않는다() throws Exception {
        HttpResponse<String> response = send("HEAD", POST_ONLY);

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("POST"));
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("HEAD"));
    }

    @Test
    void 받지_않는_메서드의_응답_본문은_다른_오류와_같은_모양이다() throws Exception {
        HttpResponse<String> response = send("GET", POST_ONLY);

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("POST"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
        assertThat(body.path("message").asString()).contains("GET");
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    @Test
    void GET_경로에_온_HEAD_는_Spring_이_GET_으로_처리해_405_가_아니다() throws Exception {
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
