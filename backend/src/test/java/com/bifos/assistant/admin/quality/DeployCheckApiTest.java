package com.bifos.assistant.admin.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.quality.application.DeployCheckRuns;
import com.bifos.assistant.quality.application.dto.OpenedDeployCheck;
import com.bifos.assistant.quality.domain.EvalResult;
import com.bifos.assistant.quality.domain.FeatureResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
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
 * 배포 뒤 확인 관리자 API 의 권한과 응답 모양, 확인 줄의 상태 전이와 실패 알림을 본다.
 *
 * <p>평가 사용자에게 에이전트를 만들지 않는다. 그래서 실행기는 Hermes 를 부르지 않고 기능마다 바로 {@code RUN_FAILED} 로 끝난다.
 * 기능별 판정은 {@code DeployCheckJudgeTest}, 실제 대화를 거치는 흐름은 가짜 Hermes e2e 가 본다.
 */
@BackendIntegrationTest
class DeployCheckApiTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8));

    private static final String CHECKS = "/api/v1/admin/quality/checks";

    @LocalServerPort
    int port;

    @Autowired
    AppUserRepository users;

    @Autowired
    QualityRunRepository runs;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    DeployCheckRuns runWrites;

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
        notifications.deleteAll();
        runs.deleteAll();
        users.deleteAll();
        admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN));
        member = users.save(AppUser.of("member@example.com", "사용자", 1L, UserRole.MEMBER));
    }


    @Test
    @DisplayName("MEMBER 는 확인 시작과 조회를 모두 거절당한다")
    void memberIsRejected() throws Exception {
        assertThat(api("POST", CHECKS, member).statusCode()).isEqualTo(403);
        assertThat(api("GET", CHECKS, member).statusCode()).isEqualTo(403);
        assertThat(runs.findAll()).isEmpty();
    }


    @Test
    @DisplayName("평가 사용자가 없으면 확인 시작은 409 EVALUATION_USER_MISSING 이다")
    void missingEvaluationUserIsConflict() throws Exception {
        HttpResponse<String> response = api("POST", CHECKS, admin);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("EVALUATION_USER_MISSING");
        assertThat(runs.findAll()).isEmpty();
    }


    @Test
    @DisplayName("확인을 시작하면 202 로 도는 줄을 돌려주고, 기능이 모두 실패로 끝나면 FAILED 와 관리자 알림이 남는다")
    void failedCheckNotifiesAdmins() throws Exception {
        evaluationUser();

        HttpResponse<String> started = api("POST", CHECKS, admin);
        JsonNode body = json.readTree(started.body());
        backgroundTasks.awaitIdle(Duration.ofSeconds(30));

        assertThat(started.statusCode()).isEqualTo(202);
        assertThat(body.path("trigger").asString()).isEqualTo("PIPELINE");
        String publicId = body.path("publicId").asString();
        JsonNode read = json.readTree(api("GET", CHECKS + "/" + publicId, admin).body());
        assertThat(read.path("status").asString()).isEqualTo("FAILED");
        assertThat(read.path("itemCount").asInt()).isEqualTo(QualityFeature.values().length);
        assertThat(read.path("results").get(0).path("reasonCode").asString()).isEqualTo("RUN_FAILED");
        // 평가 비서가 없어 Hermes 를 부르지 않은 실패는 연결과 상관없어 환경 오류가 아니다.
        assertThat(read.path("environmentError").asBoolean()).isFalse();
        assertThat(read.path("elapsedMs").isNumber()).isTrue();
        List<Notification> sent = notifications.findAll();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().userId()).isEqualTo(admin.id());
        assertThat(sent.getFirst().kind()).isEqualTo(NotificationKind.QUALITY_CHECK_FAILED);
        assertThat(sent.getFirst().targetType()).isEqualTo(NotificationTargetType.ADMIN_QUALITY);
        assertThat(sent.getFirst().body()).contains("검색과 출처").doesNotContain("RUN_FAILED");

        JsonNode listed = json.readTree(api("GET", CHECKS, admin).body());
        assertThat(listed.path("items").get(0).path("publicId").asString()).isEqualTo(publicId);
        assertThat(listed.path("nextCursor").isNull()).isTrue();

        HttpResponse<String> retried = api("POST", CHECKS + "/" + publicId + "/retry", admin);
        backgroundTasks.awaitIdle(Duration.ofSeconds(30));
        assertThat(retried.statusCode()).isEqualTo(202);
        assertThat(json.readTree(retried.body()).path("trigger").asString()).isEqualTo("ADMIN");
        assertThat(runs.findAll()).hasSize(2);
    }


    @Test
    @DisplayName("환경 오류로 실패한 기능이 있으면 응답에 environmentError 가 참이고 알림 본문이 평가 환경의 문제라고 알린다")
    void environmentErrorIsShownSeparately() throws Exception {
        AppUser evaluation = evaluationUser();
        OpenedDeployCheck opened = runWrites.open(evaluation.id(), QualityRunTrigger.PIPELINE, admin.id());
        runWrites.record(opened.runId(), new FeatureResult(QualityFeature.SEARCH, FeatureStatus.PASSED, null, 10, null, List.of()), 1);
        runWrites.record(opened.runId(),
            new FeatureResult(QualityFeature.TOOL_DISCOVERY, FeatureStatus.FAILED, "ENVIRONMENT", 10, null, List.of()), 1);

        runWrites.finish(opened.runId());

        JsonNode read = json.readTree(api("GET", CHECKS + "/" + opened.started().check().publicId(), admin).body());
        assertThat(read.path("status").asString()).isEqualTo("FAILED");
        assertThat(read.path("environmentError").asBoolean()).isTrue();
        assertThat(notifications.findAll()).singleElement()
            .satisfies(sent -> assertThat(sent.body()).contains("도구 찾기").contains("평가 환경의 문제"));
    }


    @Test
    @DisplayName("도는 확인이 있으면 새 줄을 만들지 않고 그 줄을 돌려준다")
    void runningCheckIsReused() {
        AppUser evaluation = evaluationUser();

        OpenedDeployCheck first = runWrites.open(evaluation.id(), QualityRunTrigger.PIPELINE, admin.id());
        OpenedDeployCheck second = runWrites.open(evaluation.id(), QualityRunTrigger.PIPELINE, admin.id());

        assertThat(first.started().created()).isTrue();
        assertThat(second.started().created()).isFalse();
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(runs.findAll()).hasSize(1);
    }


    @Test
    @DisplayName("서버가 다시 뜨면 도는 확인은 남은 기능을 INTERRUPTED 로 적고 FAILED 로 끝난다")
    void restartInterruptsRunningCheck() {
        QualityRun running = QualityRun.start(1L, QualityRunKind.DEPLOY_CHECK, QualityRunTrigger.PIPELINE, admin.id(), Instant.now());
        running.record(new FeatureResult(QualityFeature.SEARCH, FeatureStatus.PASSED, null, 10, null, List.of()), 1);
        Long id = runs.save(running).id();

        runWrites.interruptRunning();

        QualityRun ended = runs.findById(id).orElseThrow();
        assertThat(ended.status()).isEqualTo(QualityRunStatus.FAILED);
        assertThat(ended.results()).hasSize(QualityFeature.values().length);
        assertThat(ended.results().getLast().reasonCode()).isEqualTo("INTERRUPTED");
        assertThat(notifications.findAll()).extracting(Notification::kind).containsExactly(NotificationKind.QUALITY_CHECK_FAILED);
    }


    @Test
    @DisplayName("서버가 다시 떠도 도는 평가 줄은 배포 뒤 확인 처리가 바꾸지 않고 알림도 남기지 않는다")
    void restartLeavesEvalRunsAlone() {
        QualityRun eval = QualityRun.startEval(1L, QualityRunTrigger.ADMIN, admin.id(), 1, EvalResult.started("MEDIUM", List.of("service-concepts")),
            Instant.now());
        Long id = runs.save(eval).id();

        runWrites.interruptRunning();

        QualityRun kept = runs.findById(id).orElseThrow();
        assertThat(kept.status()).isEqualTo(QualityRunStatus.RUNNING);
        assertThat(kept.evalResult().items()).isEmpty();
        assertThat(notifications.findAll()).isEmpty();
    }


    @Test
    @DisplayName("관리자 트리 조회는 평가 사용자의 실행이 아니면 404 다")
    void treeOfOtherUserIsNotFound() throws Exception {
        evaluationUser();

        assertThat(api("GET", "/api/v1/admin/quality/executions/999999/tree", admin).statusCode()).isEqualTo(404);
        assertThat(api("GET", "/api/v1/admin/quality/executions/999999/tree", member).statusCode()).isEqualTo(403);
    }


    @Test
    @DisplayName("다른 그룹이나 없는 확인은 404 다")
    void unknownCheckIsNotFound() throws Exception {
        assertThat(api("GET", CHECKS + "/" + UUID.randomUUID(), admin).statusCode()).isEqualTo(404);
    }


    /** 에이전트 없는 평가 사용자 행이다. 실행기는 기능마다 바로 실패한다. */
    private AppUser evaluationUser() {
        AppUser saved = users.save(AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER));
        transactions.executeWithoutResult(status -> users.markEvaluation(saved.id(), Instant.now()));
        return users.findById(saved.id()).orElseThrow();
    }


    private HttpResponse<String> api(String method, String path, AppUser user) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token(user))
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
