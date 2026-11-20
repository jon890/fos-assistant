package com.bifos.assistant.admin.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.hermes.infra.HermesProfileKeyStore;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 평가 전용 사용자 관리자 API 의 권한과 응답, 관리자 사용자 목록의 「평가용」 표시, 다른 사용자에게 숨는지를 본다. */
@BackendIntegrationTest
class QualityAdminControllerTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8));

    private static final String PROFILE = "eval-check";

    private static final String PATH = "/api/v1/admin/quality/evaluation-user";

    @LocalServerPort
    int port;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    HermesProfileProvisioner profiles;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    HermesProfileKeyStore keyStore;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    /** 대역이 기억하는 profile 마다 켜진 도구다. 실행 공간 쓰기만 그림·사진 도구를 켠다. */
    private final Map<String, List<String>> enabled = new ConcurrentHashMap<>();

    private AppUser admin;
    private AppUser member;


    @BeforeEach
    void setUp() {
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
        doNothing().when(profiles).provision(any());
        when(toolsets.readCatalog()).thenReturn(List.of(
            new ToolsetCatalogEntry("web", "웹", ""),
            new ToolsetCatalogEntry("image_gen", "그림 만들기", ""),
            new ToolsetCatalogEntry("vision", "사진 보기", "")));
        when(toolsets.readEnabled(anyString(), anyString()))
            .thenAnswer(invocation -> enabled.getOrDefault(invocation.<String>getArgument(1), List.of("web")));
        doAnswer(invocation -> {
            // 첫 에이전트의 기본 도구 켜기가 요청 밖에서 겹쳐 돈다. 실제 Hermes 는 읽고 고쳐 쓰므로 겹치면 한쪽 이름이 빠질 수 있는데,
            // 그 경쟁은 다시 준비하면 회복된다(설계 「평가 전용 사용자」). 이 검사는 준비 순서만 보도록 쓴 이름을 더해 둔다.
            enabled.merge(invocation.getArgument(0), List.copyOf(invocation.<List<String>>getArgument(1)), (before, written) -> {
                LinkedHashSet<String> union = new LinkedHashSet<>(before);
                union.addAll(written);
                return List.copyOf(union);
            });
            return null;
        }).when(toolsets).writeApiServerInSandbox(anyString(), anyList(), any());
        admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN));
        member = users.save(AppUser.of("member@example.com", "사용자", 1L, UserRole.MEMBER));
    }


    @AfterEach
    void tearDown() {
        agents.findAll().stream().map(Agent::hermesProfile).filter(profile -> !PROFILE.equals(profile)).forEach(keyStore::delete);
    }


    @Test
    @DisplayName("MEMBER 는 준비와 상태 읽기를 모두 거절당한다")
    void memberIsRejected() throws Exception {
        assertThat(api("GET", PATH, token(member), null).statusCode()).isEqualTo(403);
        assertThat(api("POST", PATH, token(member), "{\"hermesProfile\":\"" + PROFILE + "\"}").statusCode()).isEqualTo(403);
        assertThat(users.findFirstByGroupIdAndEvaluationTrueOrderByIdAsc(1L)).isEmpty();
    }


    @Test
    @DisplayName("관리자가 준비하면 준비됨이고 두 번 불러도 같은 평가 사용자다")
    void adminPreparesOnce() throws Exception {
        JsonNode first = json.readTree(api("POST", PATH, token(admin), "{\"hermesProfile\":\"" + PROFILE + "\"}").body());
        JsonNode second = json.readTree(api("POST", PATH, token(admin), "{\"hermesProfile\":\"" + PROFILE + "\"}").body());

        assertThat(first.path("ready").asBoolean()).isTrue();
        assertThat(first.path("hermesProfile").asString()).isEqualTo(PROFILE);
        assertThat(second.path("ready").asBoolean()).isTrue();
        assertThat(users.findAll()).filteredOn(AppUser::evaluation).hasSize(1);
        JsonNode status = json.readTree(api("GET", PATH, token(admin), null).body());
        assertThat(status.path("missing").isEmpty()).isTrue();
    }


    @Test
    @DisplayName("profile 이름이 비면 400 이다")
    void blankProfileIsRejected() throws Exception {
        assertThat(api("POST", PATH, token(admin), "{\"hermesProfile\":\"\"}").statusCode()).isEqualTo(400);
    }


    @Test
    @DisplayName("관리자 사용자 목록이 평가 사용자를 평가용으로 표시한다")
    void peopleListMarksEvaluation() throws Exception {
        api("POST", PATH, token(admin), "{\"hermesProfile\":\"" + PROFILE + "\"}");

        JsonNode listed = json.readTree(api("GET", "/api/v1/admin/people", token(admin), null).body());

        List<Boolean> marks = new ArrayList<>();
        for (JsonNode entry : listed) {
            if (entry.path("email").asString().equals("evaluation@quality.invalid")) {
                marks.add(entry.path("evaluation").asBoolean());
            }
        }
        assertThat(marks).containsExactly(true);
    }


    @Test
    @DisplayName("다른 사용자의 에이전트 목록에 평가 사용자의 에이전트가 없고 평가 사용자 토큰은 401 이다")
    void evaluationUserIsHidden() throws Exception {
        api("POST", PATH, token(admin), "{\"hermesProfile\":\"" + PROFILE + "\"}");
        AppUser evaluation = users.findFirstByGroupIdAndEvaluationTrueOrderByIdAsc(1L).orElseThrow();
        List<String> evaluationCodes = agents.findAll().stream()
            .filter(agent -> evaluation.id().equals(agent.ownerUserId()))
            .map(Agent::code)
            .toList();

        JsonNode visible = json.readTree(api("GET", "/api/v1/agents", token(member), null).body());

        assertThat(evaluationCodes).hasSize(2);
        assertThat(visible.toString()).doesNotContain(evaluationCodes.toArray(String[]::new));
        assertThat(api("GET", "/api/v1/agents", token(evaluation), null).statusCode()).isEqualTo(401);
    }


    private HttpResponse<String> api(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
