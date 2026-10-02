package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.ServiceTokenService;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.ServiceTokenGrant;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.memory.infra.ServiceTokenCollectionRepository;
import com.bifos.assistant.memory.infra.ServiceTokenRepository;
import com.bifos.assistant.people.application.PersonRegistrar;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.people.presentation.PeopleAdminController;
import com.bifos.assistant.people.presentation.PeopleDtos.UpdatePersonRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스 토큰으로 문서를 읽는 경로를 실제 HTTP 경계에서 확인한다(ADR-056).
 *
 * <p>인증 실패 다섯 가지의 응답이 같은지, 읽을 수 없는 문서 다섯 가지의 응답이 같은지, 서비스 토큰이 사용자 API 를
 * 열지 못하는지가 핵심이다. 응답이 갈리면 토큰이나 문서가 있다는 사실이 밖으로 샌다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MemoryDocumentServiceApiTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String MARK = "평문-표식-7391";
    private static final String KID_MARK = "kid-표식-1200";
    private static final String NAME = "application-profile";
    private static final String BASE = "/api/v1/service/memory-documents/";
    private static final List<ServiceTokenGrant> IDENTITY = List.of(new ServiceTokenGrant("identity", true));

    @LocalServerPort
    int port;

    @Autowired
    MemoryService memories;

    @Autowired
    ServiceTokenService tokens;

    @Autowired
    MemoryRepository memoryRepository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    ServiceTokenRepository tokenRepository;

    @Autowired
    ServiceTokenCollectionRepository tokenCollectionRepository;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    PersonRegistrar registrar;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private CurrentUser dad;
    private CurrentUser kid;
    private AllowedPerson dadPerson;
    private String dadEmail;

    @BeforeEach
    void setUp() {
        cleanUp();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        dadEmail = "svc-dad-" + suffix + "@example.com";
        dadPerson = people.save(AllowedPerson.of(dadEmail, "dad", "svc-dad-" + suffix));
        dad = saveUser(dadEmail, "dad", UserRole.ADMIN);
        people.save(AllowedPerson.of("svc-kid-" + suffix + "@example.com", "kid", "svc-kid-" + suffix));
        kid = saveUser("svc-kid-" + suffix + "@example.com", "kid", UserRole.MEMBER);

        memories.createDocument(dad, "identity", NAME, "지원서 공통 프로필", MARK, MemorySensitivity.SENSITIVE);
        memories.createDocument(dad, "career", "position-notes", "직무 메모", "일반 글", MemorySensitivity.NORMAL);
        memories.createDocument(kid, "identity", NAME, "kid 문서", KID_MARK, MemorySensitivity.SENSITIVE);
    }

    @AfterEach
    void cleanUp() {
        revisionRepository.deleteAll();
        memoryRepository.deleteAll();
        tokenCollectionRepository.deleteAll();
        tokenRepository.deleteAll();
        people.findAll().stream()
                .filter(person -> person.email().startsWith("svc-"))
                .forEach(people::delete);
        users.findAll().stream().filter(user -> user.email().startsWith("svc-")).forEach(users::delete);
    }

    private CurrentUser saveUser(String email, String name, UserRole role) {
        AppUser saved = users.save(AppUser.of(email, name, 1L, role));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), role);
    }

    private String issue(CurrentUser owner, int days, List<ServiceTokenGrant> grants) {
        return tokens.issue(owner, "e2e", days, grants).rawToken();
    }

    private HttpResponse<String> get(String path, String bearer, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> read(String bearer, String collection, String key) throws Exception {
        return get(BASE + collection + "/" + key, bearer, null);
    }

    /** 응답이 같은지 견줄 때 쓰는 머리말이다. 시각을 담는 머리말은 요청마다 달라 뺀다. */
    private static Map<String, List<String>> stableHeaders(HttpResponse<String> response) {
        Map<String, List<String>> headers = new TreeMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("date")) {
                headers.put(name.toLowerCase(), values);
            }
        });
        return headers;
    }

    private void assertSameAsWrongToken(HttpResponse<String> response) throws Exception {
        HttpResponse<String> wrong = read("fos_svc_wrong", "identity", NAME);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(response.statusCode()).isEqualTo(wrong.statusCode());
        assertThat(response.body()).isEqualTo(wrong.body());
        assertThat(stableHeaders(response)).isEqualTo(stableHeaders(wrong));
        assertThat(response.headers().firstValue("www-authenticate")).isEmpty();
    }

    private String lastUsedAt(String bearerHashSource) {
        return jdbc.queryForObject(
                "SELECT CAST(last_used_at AS VARCHAR) FROM service_token WHERE token_hash = ?",
                String.class,
                Sha256.hex(bearerHashSource));
    }

    @Test
    @DisplayName("맞는 토큰으로 본문과 판 번호를 읽고 캐시하지 않으며 만료 시각을 알린다")
    void readsDocumentWithRevisionAndHeaders() throws Exception {
        String token = issue(dad, 90, IDENTITY);

        HttpResponse<String> response = read(token, "identity", NAME);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("content").asString()).isEqualTo(MARK);
        assertThat(body.get("revision").asInt()).isEqualTo(1);
        assertThat(body.get("collection").asString()).isEqualTo("identity");
        assertThat(body.get("documentKey").asString()).isEqualTo(NAME);
        assertThat(body.get("title").asString()).isNotBlank();
        assertThat(body.get("updatedAt").asString()).isNotBlank();
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        Instant expires = Instant.parse(
                response.headers().firstValue("X-Service-Token-Expires-At").orElseThrow());
        assertThat(Duration.between(Instant.now().plus(Duration.ofDays(90)), expires)
                        .abs())
                .isLessThan(Duration.ofMinutes(1));
        assertThat(lastUsedAt(token)).isNotNull();
    }

    @Test
    @DisplayName("문서를 고치면 판 번호가 오르고 고친 글을 읽는다")
    void readsRevisedDocument() throws Exception {
        String token = issue(dad, 90, IDENTITY);
        Long id = memoryRepository
                .findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(MemoryScope.USER, dad.id(), "identity", NAME)
                .orElseThrow()
                .id();
        memories.reviseDocument(dad, id, "평문-표식-8802", MemorySensitivity.SENSITIVE, 1);

        JsonNode body = json.readTree(read(token, "identity", NAME).body());

        assertThat(body.get("revision").asInt()).isEqualTo(2);
        assertThat(body.get("content").asString()).isEqualTo("평문-표식-8802");
    }

    @Test
    @DisplayName("토큰이 없거나 틀리거나 폐기되거나 만료되면 모두 같은 401 이다")
    void unauthenticatedCasesAreIdentical() throws Exception {
        assertSameAsWrongToken(read(null, "identity", NAME));

        String revoked = issue(dad, 90, IDENTITY);
        tokens.revoke(dad, tokens.listOf(dad).get(0).token().id());
        assertSameAsWrongToken(read(revoked, "identity", NAME));

        String expired = issue(dad, 90, IDENTITY);
        jdbc.update(
                "UPDATE service_token SET expires_at = TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(6))"
                        + " WHERE token_hash = ?",
                Sha256.hex(expired));
        assertSameAsWrongToken(read(expired, "identity", NAME));
    }

    @Test
    @DisplayName("웹 JWT 를 실어도 서비스 경로는 401 이다")
    void webJwtDoesNotOpenServiceApi() throws Exception {
        Instant now = Instant.now();
        String jwt = Jwts.builder()
                .subject(dadEmail)
                .claim("name", "dad")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertSameAsWrongToken(read(jwt, "identity", NAME));
    }

    @Test
    @DisplayName("주인이 허용 목록에서 꺼지면 사건 없이도 401 이고 토큰 줄은 그대로이며 다시 켜면 통한다")
    void ownerDisabledInAllowListIsRejectedPerRequest() throws Exception {
        String token = issue(dad, 90, IDENTITY);
        assertThat(read(token, "identity", NAME).statusCode()).isEqualTo(200);
        String usedBefore = lastUsedAt(token);

        dadPerson.disable();
        people.save(dadPerson);

        assertSameAsWrongToken(read(token, "identity", NAME));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_token WHERE revoked_at IS NOT NULL", Long.class))
                .isZero();
        assertThat(lastUsedAt(token)).isEqualTo(usedBefore);

        dadPerson.enable();
        people.save(dadPerson);

        assertThat(read(token, "identity", NAME).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("관리자 경로로 사용자를 끄면 토큰이 폐기되고 다시 켜도 되살아나지 않는다")
    void adminDisableRevokesAndEnableDoesNotRestore() throws Exception {
        String token = issue(dad, 90, IDENTITY);
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.require()).thenReturn(dad);
        PeopleAdminController admin = new PeopleAdminController(people, users, registrar, provider, events);

        admin.update(dadPerson.id(), new UpdatePersonRequest(false));
        admin.update(dadPerson.id(), new UpdatePersonRequest(true));

        assertSameAsWrongToken(read(token, "identity", NAME));
        assertThat(tokenRepository.findAll())
                .allSatisfy(row -> assertThat(row.revokedAt()).isNotNull());
    }

    @Test
    @DisplayName("허용 목록에 줄이 없는 사용자의 토큰은 401 이다")
    void ownerWithoutAllowListRowIsRejected() throws Exception {
        CurrentUser stranger = saveUser("svc-stranger-" + UUID.randomUUID() + "@example.com", "x", UserRole.MEMBER);

        assertSameAsWrongToken(read(issue(stranger, 90, IDENTITY), "identity", NAME));
    }

    @Test
    @DisplayName("Origin 이 있으면 맞는 토큰이어도 403 이다")
    void originIsForbidden() throws Exception {
        HttpResponse<String> response = get(BASE + "identity/" + NAME, issue(dad, 90, IDENTITY), "https://example.com");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("서비스 토큰으로 사용자 API 를 부르면 403 이다")
    void serviceTokenDoesNotOpenUserApi() throws Exception {
        HttpResponse<String> response = get("/api/v1/memories", issue(dad, 90, IDENTITY), null);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("없는 문서와 받지 않는 collection 과 민감 허용 없는 민감 문서는 글자까지 같은 404 이다")
    void unreadableDocumentsAreIdentical404() throws Exception {
        String careerOnly = issue(dad, 90, List.of(new ServiceTokenGrant("career", true)));
        String noSensitive = issue(dad, 90, List.of(new ServiceTokenGrant("identity", false)));
        String full = issue(dad, 90, IDENTITY);

        HttpResponse<String> missing = read(full, "identity", "no-such-document");
        HttpResponse<String> notGranted = read(careerOnly, "identity", NAME);
        HttpResponse<String> sensitiveDenied = read(noSensitive, "identity", NAME);

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json.readTree(missing.body()).get("code").asString()).isEqualTo("MEMORY_NOT_FOUND");
        for (HttpResponse<String> other : List.of(notGranted, sensitiveDenied)) {
            assertThat(other.statusCode()).isEqualTo(404);
            assertThat(other.body()).isEqualTo(missing.body());
            assertThat(stableHeaders(other)).isEqualTo(stableHeaders(missing));
        }
    }

    @Test
    @DisplayName("같은 이름의 남의 문서는 나오지 않고 내게 없는 이름은 404 이다")
    void neverReturnsOthersDocument() throws Exception {
        String token = issue(dad, 90, IDENTITY);

        assertThat(read(token, "identity", NAME).body())
                .doesNotContain(KID_MARK)
                .contains(MARK);

        memories.createDocument(kid, "identity", "kid-only", "kid 만", KID_MARK, MemorySensitivity.NORMAL);
        assertThat(read(token, "identity", "kid-only").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("승인 전인 항목과 문서가 아닌 항목은 서비스가 읽지 못한다")
    void proposedAndNonDocumentEntriesAreHidden() {
        for (String[] row :
                new String[][] {{"DOCUMENT", "PROPOSED", "proposed-doc"}, {"MEMORY", "ACCEPTED", "memory-with-key"}}) {
            jdbc.update("""
                    INSERT INTO memory (scope, owner_user_id, collection, entry_type, document_key, title, content,
                        retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                    VALUES ('USER', ?, 'identity', ?, ?, '제목', '본문', 'SEARCH', FALSE, 'NORMAL', 1, ?,
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, dad.id(), row[0], row[2], row[1]);
            assertThatThrownBy(() -> memories.documentForService(
                            dad.id(), MemoryAccess.of(Set.of("identity"), Set.of("identity")), "identity", row[2]))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
        }
    }
}
