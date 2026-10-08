package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
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
    private static final SecretKey OTHER_KEY =
            Keys.hmacShaKeyFor("other-secret-other-secret-other-secret-other".getBytes(StandardCharsets.UTF_8));

    @LocalServerPort
    int port;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    TestClock clock;

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

    @Test
    @DisplayName("로그인 완료는 서버 시각을 기록하고 이전 시각으로 되돌리지 않는다")
    void recordsLoginCompletionWithoutMovingBackwards() throws Exception {
        Instant first = Instant.parse("2026-10-08T01:00:00Z");
        clock.set(first);

        assertThat(complete(signInToken(), "MOM@EXAMPLE.COM").statusCode()).isEqualTo(204);
        assertThat(people.findByEmailAndEnabledTrue("mom@example.com")
                        .orElseThrow()
                        .lastLoginAt())
                .isEqualTo(first);

        Instant second = first.plusSeconds(60);
        clock.set(second);
        assertThat(complete(signInToken(), "mom@example.com").statusCode()).isEqualTo(204);
        assertThat(people.findByEmailAndEnabledTrue("mom@example.com")
                        .orElseThrow()
                        .lastLoginAt())
                .isEqualTo(second);

        assertThat(complete(signInToken(), "mom@example.com").statusCode()).isEqualTo(204);
        assertThat(people.findByEmailAndEnabledTrue("mom@example.com").orElseThrow().lastLoginAt())
                .isEqualTo(second);

        clock.set(first.minusSeconds(60));
        assertThat(complete(signInToken(), "mom@example.com").statusCode()).isEqualTo(204);
        assertThat(people.findByEmailAndEnabledTrue("mom@example.com")
                        .orElseThrow()
                        .lastLoginAt())
                .isEqualTo(second);

        clock.set(second.plusSeconds(60));
        assertThat(ask(signInToken(), "mom@example.com").statusCode()).isEqualTo(200);
        assertThat(people.findByEmailAndEnabledTrue("mom@example.com")
                        .orElseThrow()
                        .lastLoginAt())
                .isEqualTo(second);
        assertThat(users.count()).isZero();
    }

    @Test
    @DisplayName("일반 인증 요청은 기록된 로그인 시각을 갱신하지 않는다")
    void ordinaryRequestDoesNotRecordAnotherLogin() throws Exception {
        Instant login = Instant.parse("2026-10-08T01:00:00Z");
        clock.set(login);
        assertThat(complete(signInToken(), "mom@example.com").statusCode()).isEqualTo(204);
        users.save(AppUser.of("mom@example.com", "사용자", 1L, UserRole.MEMBER, login));
        clock.set(login.plusSeconds(60));

        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + conversationToken("mom@example.com"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(people.findByEmailAndEnabledTrue("mom@example.com").orElseThrow().lastLoginAt())
                .isEqualTo(login);
    }

    @Test
    @DisplayName("로그인 완료는 잘못된 토큰과 허용되지 않은 주소를 거절하고 사용자를 만들지 않는다")
    void rejectsInvalidOrDisallowedCompletionWithoutCreatingUser() throws Exception {
        assertThat(complete(null, "mom@example.com").statusCode()).isEqualTo(401);
        assertThat(complete(conversationToken("mom@example.com"), "mom@example.com")
                        .statusCode())
                .isEqualTo(401);
        assertThat(complete(forgedSignInToken(), "mom@example.com").statusCode())
                .isEqualTo(401);
        assertThat(complete(expiredSignInToken(), "mom@example.com").statusCode())
                .isEqualTo(401);
        assertThat(complete(signInToken(), "").statusCode()).isEqualTo(401);
        assertThat(complete(signInToken(), "stranger@example.com").statusCode()).isEqualTo(401);

        AllowedPerson person =
                people.findByEmailAndEnabledTrue("mom@example.com").orElseThrow();
        person.disable();
        people.save(person);
        assertThat(complete(signInToken(), "mom@example.com").statusCode()).isEqualTo(401);
        assertThat(people.findById(person.id()).orElseThrow().lastLoginAt()).isNull();
        assertThat(users.count()).isZero();
    }

    private HttpResponse<String> ask(String token, String email) throws Exception {
        return request("/api/v1/signin/allowed", token, email);
    }

    private HttpResponse<String> complete(String token, String email) throws Exception {
        return request("/api/v1/signin/completed", token, email);
    }

    private HttpResponse<String> request(String path, String token, String email) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
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

    private String forgedSignInToken() {
        return Jwts.builder()
                .claim("purpose", "signin")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(OTHER_KEY)
                .compact();
    }

    private String expiredSignInToken() {
        return Jwts.builder()
                .claim("purpose", "signin")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(KEY)
                .compact();
    }
}
