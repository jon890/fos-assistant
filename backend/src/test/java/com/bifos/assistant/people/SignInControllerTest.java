package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 HTTP 경계에서 로그인 판정 경로를 확인한다.
 *
 * <p>이 경로는 Spring Security 에서 열려 있고 사용자를 만드는 필터도 건너뛴다. 그래서 어떤 토큰을 받고
 * 어떤 토큰을 거절하는지, 그리고 거절한 뒤에 사용자가 남지 않는지를 여기서 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SignInControllerTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));

    @LocalServerPort int port;
    @Autowired AllowedPersonRepository people;
    @Autowired AppUserRepository users;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void 준비한다() {
        people.deleteAll();
        users.deleteAll();
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));
    }

    @Test
    void 로그인_판정용_토큰은_허용된_사람을_돌려준다() throws Exception {
        HttpResponse<String> response = ask(signInToken(), "mom@example.com");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("allowed").asBoolean()).isTrue();
        assertThat(body.path("displayName").asString()).isEqualTo("엄마");
        assertThat(body.path("hermesProfile").asString()).isEqualTo("mom");
    }

    @Test
    void 토큰이_없으면_거절한다() throws Exception {
        assertThat(ask(null, "mom@example.com").statusCode()).isEqualTo(401);
    }

    @Test
    void 대화용_토큰으로는_부를_수_없다() throws Exception {
        assertThat(ask(conversationToken("mom@example.com"), "mom@example.com").statusCode())
                .isEqualTo(401);
    }

    @Test
    void 허용되지_않은_주소를_물어도_사용자가_생기지_않는다() throws Exception {
        long before = users.count();

        HttpResponse<String> response = ask(signInToken(), "stranger@example.com");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("allowed").asBoolean()).isFalse();
        assertThat(users.count()).isEqualTo(before);
    }

    @Test
    void 빈_주소는_허용_목록에_없는_주소와_같은_답을_받는다() throws Exception {
        HttpResponse<String> response = ask(signInToken(), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("allowed").asBoolean()).isFalse();
    }

    @Test
    void 토큰_검사가_본문_검사보다_먼저_돈다() throws Exception {
        assertThat(ask(null, "").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> ask(String token, String email) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/signin/allowed"))
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
