package com.bifos.assistant.admin.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.quality.domain.EvalResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunTrigger;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import com.bifos.assistant.user.domain.AppUser;
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
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 묶음 평가 관리자 API 의 권한, 응답 모양과 시작 규칙을 본다.
 *
 * <p>평가 사용자에게 에이전트를 만들지 않는다. 그래서 실행기는 Hermes 를 부르지 않고 바로 실패로 끝난다. 문항을 돌리는 흐름은 가짜 Hermes e2e 가 본다.
 */
@BackendIntegrationTest
class EvalApiTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8));

    private static final String EVALS = "/api/v1/admin/quality/evals";

    @LocalServerPort
    int port;

    @Autowired
    AppUserRepository users;

    @Autowired
    QualityRunRepository runs;

    @Autowired
    TrackingBackgroundTasks backgroundTasks;

    @Autowired
    TransactionTemplate transactions;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private AppUser admin;
    private AppUser member;


    @BeforeEach
    void setUp() {
        runs.deleteAll();
        users.deleteAll();
        admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN));
        member = users.save(AppUser.of("member@example.com", "사용자", 1L, UserRole.MEMBER));
    }


    @Test
    @DisplayName("MEMBER 는 묶음 목록, 평가 시작, 평가 조회를 모두 거절당한다")
    void memberIsRejected() throws Exception {
        assertThat(api("GET", "/api/v1/admin/quality/eval-bundles", member, null).statusCode()).isEqualTo(403);
        assertThat(api("POST", EVALS, member, "{}").statusCode()).isEqualTo(403);
        assertThat(api("GET", EVALS, member, null).statusCode()).isEqualTo(403);
        assertThat(runs.findAll()).isEmpty();
    }


    @Test
    @DisplayName("묶음 목록은 서비스 개념 묶음을 문항 수와 상한과 함께 보인다")
    void listsBundles() throws Exception {
        JsonNode body = json.readTree(api("GET", "/api/v1/admin/quality/eval-bundles", admin, null).body());

        JsonNode concepts = null;
        for (JsonNode bundle : body) {
            if ("service-concepts".equals(bundle.path("id").asString())) {
                concepts = bundle;
            }
        }
        assertThat(concepts).isNotNull();
        assertThat(concepts.path("questionCount").asInt()).isPositive();
        assertThat(concepts.path("timeoutMinutes").asInt()).isEqualTo(10);
    }


    @Test
    @DisplayName("평가 사용자가 없으면 409, 모르는 묶음과 모르는 단계는 400 이다")
    void rejectsBadStarts() throws Exception {
        HttpResponse<String> missing = api("POST", EVALS, admin, "{}");
        assertThat(missing.statusCode()).isEqualTo(409);
        assertThat(missing.body()).contains("EVALUATION_USER_MISSING");

        evaluationUser();
        HttpResponse<String> unknown = api("POST", EVALS, admin, "{\"bundles\":[\"no-such-bundle\"]}");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("QUALITY_EVAL_BUNDLE_UNKNOWN");
        assertThat(api("POST", EVALS, admin, "{\"tier\":\"NO_SUCH_TIER\"}").statusCode()).isEqualTo(400);
        assertThat(runs.findAll()).isEmpty();
    }


    @Test
    @DisplayName("묶음을 비우면 전체를 고르고, 고른 단계가 줄에 적히며, 끝난 평가가 목록과 조회에 보인다")
    void startsAllBundlesWithChosenTier() throws Exception {
        evaluationUser();

        HttpResponse<String> started = api("POST", EVALS, admin, "{\"tier\":\"HIGH\"}");
        backgroundTasks.awaitIdle(Duration.ofSeconds(30));

        assertThat(started.statusCode()).isEqualTo(202);
        JsonNode body = json.readTree(started.body());
        assertThat(body.path("tier").asString()).isEqualTo("HIGH");
        assertThat(body.path("bundles").toString()).contains("service-concepts", "answer-quality");
        String publicId = body.path("publicId").asString();
        JsonNode read = json.readTree(api("GET", EVALS + "/" + publicId, admin, null).body());
        assertThat(read.path("status").asString()).isEqualTo("FAILED");
        for (JsonNode bundle : read.path("bundles")) {
            // 답변 품질 묶음은 밖에서 채점해 EXTERNAL, 서비스 개념 묶음은 에이전트가 없어 FAILED 다.
            String expected = "answer-quality".equals(bundle.path("id").asString()) ? "EXTERNAL" : "FAILED";
            assertThat(bundle.path("status").asString()).as(bundle.path("id").asString()).isEqualTo(expected);
        }
        assertThat(read.path("items").toString()).doesNotContain("conversationId\"");
        JsonNode listed = json.readTree(api("GET", EVALS, admin, null).body());
        assertThat(listed.path("items").get(0).path("publicId").asString()).isEqualTo(publicId);
    }


    @Test
    @DisplayName("도는 평가가 있으면 새로 만들지 않고 200 과 그 줄을 돌려준다")
    void runningEvalIsReused() throws Exception {
        evaluationUser();
        QualityRun running = runs.save(QualityRun.startEval(1L, QualityRunTrigger.ADMIN, admin.id(), 1,
            EvalResult.started("MEDIUM", List.of("service-concepts")), Instant.now()));

        HttpResponse<String> second = api("POST", EVALS, admin, "{}");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("publicId").asString()).isEqualTo(running.publicId().toString());
        assertThat(runs.findAll()).hasSize(1);
    }


    @Test
    @DisplayName("평가 사용자의 것이 아닌 대화와 없는 평가는 404 다")
    void otherConversationIsNotFound() throws Exception {
        evaluationUser();

        assertThat(api("GET", "/api/v1/admin/quality/conversations/" + UUID.randomUUID() + "/messages", admin, null).statusCode())
            .isEqualTo(404);
        assertThat(api("GET", EVALS + "/" + UUID.randomUUID(), admin, null).statusCode()).isEqualTo(404);
    }


    /** 에이전트 없는 평가 사용자 행이다. 실행기는 바로 실패한다. */
    private AppUser evaluationUser() {
        AppUser saved = users.save(AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER));
        transactions.executeWithoutResult(status -> users.markEvaluation(saved.id(), Instant.now()));
        return users.findById(saved.id()).orElseThrow();
    }


    private HttpResponse<String> api(String method, String path, AppUser user, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token(user));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
