package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 HTTP 경계에서 로그인 판정 경로를 확인한다.
 *
 * <p>이 경로는 Spring Security 에서 열려 있고 사용자를 만드는 필터도 건너뛴다. 그래서 어떤 토큰을 받고
 * 어떤 토큰을 거절하는지, 그리고 거절한 뒤에 사용자가 남지 않는지를 여기서 본다.
 */
@BackendIntegrationTest
class SignInControllerTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));

    @LocalServerPort
    int port;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        people.deleteAll();
        users.deleteAll();
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom", Instant.now()));
    }

    @Test
    @DisplayName("로그인 판정용 토큰은 허용된 사람을 돌려준다")
    void signInJudgeTokenReturnsAllowedPerson() throws Exception {
        HttpResponse<String> response = ask(signInToken(), "mom@example.com");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("allowed").asBoolean()).isTrue();
        assertThat(body.path("displayName").asString()).isEqualTo("엄마");
        assertThat(body.path("hermesProfile").asString()).isEqualTo("mom");
    }

    @Test
    @DisplayName("토큰이 없으면 거절한다")
    void rejectsWhenTokenIsMissing() throws Exception {
        assertThat(ask(null, "mom@example.com").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("대화용 토큰으로는 부를 수 없다")
    void cannotBeCalledWithConversationToken() throws Exception {
        assertThat(ask(conversationToken("mom@example.com"), "mom@example.com").statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("허용되지 않은 주소를 물어도 사용자가 생기지 않는다")
    void asksDisallowedAddressWithoutCreatingUser() throws Exception {
        long before = users.count();

        HttpResponse<String> response = ask(signInToken(), "stranger@example.com");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("allowed").asBoolean()).isFalse();
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("빈 주소는 허용 목록에 없는 주소와 같은 답을 받는다")
    void blankAddressGetsSameAnswerAsAddressNotInAllowlist() throws Exception {
        HttpResponse<String> response = ask(signInToken(), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("allowed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("토큰 검사가 본문 검사보다 먼저 돈다")
    void tokenCheckRunsBeforeBodyCheck() throws Exception {
        assertThat(ask(null, "").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> ask(String token, String email) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/signin/allowed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\"}"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 웹 계층이 로그인 판정에만 쓰는 토큰이다. 신원을 담지 않는다. */
    private String signInToken() {
        return Jwts.builder()
                .claim("purpose", "signin")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(KEY)
                .compact();
    }

    /** 대화를 부를 때 쓰는 토큰이다. 서명은 같지만 쓰임새가 다르다. */
    private String conversationToken(String email) {
        return Jwts.builder()
                .subject(email)
                .claim("name", email)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(KEY)
                .compact();
    }
}
