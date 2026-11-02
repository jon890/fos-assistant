package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.application.PersonAccessService;
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

/** 사용자 정리 예약과 취소가 꺼진 사람에게만 걸리고 관리자만 부를 수 있는지 확인한다. */
@BackendIntegrationTest
class PersonPurgeScheduleTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8));

    @LocalServerPort
    int port;

    @Autowired
    PersonAccessService access;

    @Autowired
    TestClock clock;

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
    }


    @Test
    @DisplayName("꺼진 사람에 예약하면 시각이 적히고 다시 켜면 비워진다")
    void scheduleRecordsTimeAndEnableClearsIt() {
        AllowedPerson person = disabledPerson("schedule@example.com", "schedule");
        Instant at = Instant.parse("2026-11-02T01:00:00Z");
        clock.set(at);

        assertThat(access.schedulePurge(person.id()).person().purgeScheduledAt()).isEqualTo(at);
        assertThat(people.findById(person.id()).orElseThrow().purgeScheduledAt()).isEqualTo(at);

        access.setEnabled(person.id(), true);

        assertThat(people.findById(person.id()).orElseThrow().purgeScheduledAt()).isNull();
    }


    @Test
    @DisplayName("켜진 사람에 예약하면 거절한다")
    void scheduleRejectsEnabledPerson() throws Exception {
        AppUser admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN));
        AllowedPerson person = people.save(AllowedPerson.of("enabled@example.com", "켜짐", "enabled"));

        HttpResponse<String> response = api("POST", "/api/v1/admin/people/" + person.id() + "/purge-schedule", token(admin));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(people.findById(person.id()).orElseThrow().purgeScheduledAt()).isNull();
    }


    @Test
    @DisplayName("이미 예약한 사람에 다시 예약해도 시각이 그대로다")
    void scheduleKeepsFirstTime() {
        AllowedPerson person = disabledPerson("again@example.com", "again");
        Instant first = Instant.parse("2026-11-02T01:00:00Z");
        clock.set(first);
        access.schedulePurge(person.id());
        clock.set(first.plusSeconds(3600));

        access.schedulePurge(person.id());

        assertThat(people.findById(person.id()).orElseThrow().purgeScheduledAt()).isEqualTo(first);
    }


    @Test
    @DisplayName("예약을 취소하면 비워지고 응답에 예약 시각이 실린다")
    void cancelClearsAndResponseCarriesTime() throws Exception {
        AppUser admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN));
        AllowedPerson person = disabledPerson("cancel@example.com", "cancel");
        String path = "/api/v1/admin/people/" + person.id() + "/purge-schedule";
        clock.set(Instant.parse("2026-11-02T01:00:00Z"));

        JsonNode scheduled = json.readTree(api("POST", path, token(admin)).body());
        assertThat(scheduled.path("purgeScheduledAt").asString()).isEqualTo("2026-11-02T01:00:00Z");

        JsonNode cancelled = json.readTree(api("DELETE", path, token(admin)).body());
        assertThat(cancelled.path("purgeScheduledAt").isNull()).isTrue();
        assertThat(people.findById(person.id()).orElseThrow().purgeScheduledAt()).isNull();
    }


    @Test
    @DisplayName("MEMBER 는 예약과 취소를 모두 거절당한다")
    void memberIsRejected() throws Exception {
        AppUser member = users.save(AppUser.of("member@example.com", "사용자", 1L, UserRole.MEMBER));
        AllowedPerson person = disabledPerson("target@example.com", "target");
        String path = "/api/v1/admin/people/" + person.id() + "/purge-schedule";

        assertThat(api("POST", path, token(member)).statusCode()).isEqualTo(403);
        assertThat(api("DELETE", path, token(member)).statusCode()).isEqualTo(403);
        assertThat(people.findById(person.id()).orElseThrow().purgeScheduledAt()).isNull();
    }


    private AllowedPerson disabledPerson(String email, String profile) {
        AllowedPerson person = AllowedPerson.of(email, "대상", profile);
        person.disable();
        return people.save(person);
    }


    private HttpResponse<String> api(String method, String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }


    private String token(AppUser user) {
        return Jwts.builder()
            .subject(user.email())
            .claim("name", user.displayName())
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(120)))
            .signWith(KEY)
            .compact();
    }
}
