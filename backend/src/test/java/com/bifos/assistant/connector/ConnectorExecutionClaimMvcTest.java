package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.shared.auth.AuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 HTTP의 SecurityFilterChain, 원문 controller, 서비스, DB를 왕복한다. */
class ConnectorExecutionClaimMvcTest extends ConnectorExecutionClaimTest {
    private static final String CLAIM = "/internal/connector-executions/claim";

    @LocalServerPort
    int port;

    @Autowired
    AuthProperties auth;

    @Test
    @DisplayName("전체 필터를 통과한 ticket claim은 200, 같은 권한의 재사용은 409다")
    void authenticatesTicketAcrossEntireFilterChain() throws Exception {
        Fixture f = fixture();
        String raw = tickets.issue(f.action());
        String request = JSON.writeValueAsString(input(raw));
        var response = post(CLAIM, request, null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        var result = JSON.readTree(response.body());
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("v", "allowed", "ticketId", "expiresAt");
        assertThat(result.get("allowed").booleanValue()).isTrue();
        assertThat(post(CLAIM, request, null, false).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("유효 JWT와 profile Bearer만으로 본문 ticket을 대신 인증할 수 없다")
    void rejectsJwtAndProfileBearerWithoutTicket() throws Exception {
        Fixture f = fixture();
        String jwt = Jwts.builder()
                .subject(f.user().email())
                .signWith(Keys.hmacShaKeyFor(auth.jwtSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();
        for (String token : new String[] {jwt, "test-profile-bearer"}) {
            assertThat(post(CLAIM, "{\"tool\":\"place_order\"}", token, false).statusCode())
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("중복 키와 chunked 초과 본문은 400이며 소비가 일어나지 않는다")
    void rejectsDuplicateAndOversizedChunkedBodies() throws Exception {
        Fixture f = fixture();
        String request = JSON.writeValueAsString(input(tickets.issue(f.action())));
        assertThat(post(CLAIM, request.replace("\"tool\":", "\"tool\":\"place_order\",\"tool\":"), null, false)
                        .statusCode())
                .isEqualTo(400);
        assertThat(post(CLAIM, " ".repeat(8192) + request, null, true).statusCode())
                .isEqualTo(400);
        assertThat(row(f).consumedAt()).isNull();
    }

    @Test
    @DisplayName("claim의 하위나 비슷한 내부 경로는 익명으로 열리지 않는다")
    void keepsNeighboringInternalPathsAuthenticated() throws Exception {
        for (String uri : new String[] {CLAIM + "/extra", CLAIM + "-extra", "/internal/connector-executions/support"}) {
            assertThat(post(uri, "{}", null, false).statusCode()).isIn(401, 403);
        }
    }

    private HttpResponse<String> post(String uri, String body, String token, boolean chunked) throws Exception {
        var publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                : HttpRequest.BodyPublishers.ofString(body);
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + uri))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(publisher);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
