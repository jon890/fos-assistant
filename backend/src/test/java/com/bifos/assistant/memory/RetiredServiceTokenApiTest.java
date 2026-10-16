package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/** 폐기한 API가 인증 우회로 남지 않고 현재 사용자 기억 API는 계속 열리는지 실제 HTTP로 확인한다. */
@BackendIntegrationTest
class RetiredServiceTokenApiTest {
    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String RETIRED_TOKEN = "fos_svc_retired-synthetic-token";

    @LocalServerPort
    private int port;

    @Autowired
    private AllowedPersonRepository people;
    @Autowired
    private AppUserRepository users;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;

    private final HttpClient client = HttpClient.newHttpClient();
    private AllowedPerson person;
    private AppUser user;
    private String jwt;


    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        String email = "retired-service-" + suffix + "@example.com";
        person = people.save(AllowedPerson.of(email, "사용자", "retired-" + suffix, Instant.now()));
        user = users.save(AppUser.of(email, "사용자", 9_970_001L, UserRole.MEMBER, Instant.now()));
        jwt = Jwts.builder().subject(email).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }


    @AfterEach
    void tearDown() {
        users.deleteById(user.id());
        people.deleteById(person.id());
    }


    @ParameterizedTest
    @CsvSource({
        "GET,/api/v1/service-tokens",
        "POST,/api/v1/service-tokens",
        "DELETE,/api/v1/service-tokens/1",
        "GET,/api/v1/service/memory-documents/identity/profile"
    })
    @DisplayName("폐기한 발급·조회·폐기·문서 API의 컨트롤러 매핑은 존재하지 않는다")
    void removesRetiredEndpointMappings(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(request);
        assertThat(handlers.getHandler(request)).isNull();
    }


    @ParameterizedTest
    @CsvSource({
        "GET,/api/v1/service-tokens",
        "POST,/api/v1/service-tokens",
        "DELETE,/api/v1/service-tokens/1",
        "GET,/api/v1/service/memory-documents/identity/profile"
    })
    @DisplayName("폐기한 API에도 인증이 필요하며 서비스 토큰 모양의 값으로 우회할 수 없다")
    void rejectsAnonymousAndRetiredBearer(String method, String path) throws Exception {
        assertThat(send(method, path, null).statusCode()).isEqualTo(403);
        assertThat(send(method, path, RETIRED_TOKEN).statusCode()).isEqualTo(403);
    }


    @Test
    @DisplayName("현재 기억 목록은 사용자 JWT로 계속 읽고 서비스 토큰으로 읽지 못한다")
    void preservesUserMemoryAuthentication() throws Exception {
        assertThat(send("GET", "/api/v1/memories", jwt).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/memories", RETIRED_TOKEN).statusCode()).isEqualTo(403);
    }


    private HttpResponse<String> send(String method, String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
