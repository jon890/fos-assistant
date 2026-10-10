package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.presentation.ChatDtos.MediaObservationCorrectionRequest;
import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.shared.auth.CurrentUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

class MediaObservationControllerTest extends ObservationFixture {
    private static final String SECRET = "SYNTHETIC_OCR_PRIVATE_4817";

    @LocalServerPort
    int port;

    @Autowired
    KeyEncryptionKeys keks;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("HTTP 정정과 UUID 재시도는 최초 revision과 시각을 보존한다")
    void correctsAndRetriesWithoutChangingRevisionOrObservedAt() throws Exception {
        var before = body(get(owner, ""));
        assertThat(before.path("items").get(0).path("status").asString()).isEqualTo("NOT_ANALYZED");
        UUID requestId = UUID.randomUUID();
        String request = correction(0, requestId, "사람이 확인");
        var first = put(owner, photo.id().toString(), request);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var result = body(first);
        assertThat(result.path("sourceAssurance").asString()).isEqualTo("USER_CORRECTION");
        assertThat(result.path("provenance").path("provider").isNull()).isTrue();
        clock.advance(Duration.ofSeconds(5));
        assertThat(body(put(owner, photo.id().toString(), request))).isEqualTo(result);
        assertThat(put(owner, photo.id().toString(), correction(0, requestId, "다른 내용"))
                        .statusCode())
                .isEqualTo(409);
        assertThat(put(owner, photo.id().toString(), correction(0, UUID.randomUUID(), "이전 revision"))
                        .statusCode())
                .isEqualTo(409);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(body(get(owner, "")).path("items").get(0)).isEqualTo(result);
    }

    @Test
    @DisplayName("HTTP CAS 경합은 수락한 본문과 alias 하나만 저장한다")
    void racingHttpCorrectionsCommitOneRevisionAndMatchingAlias() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = List.of("첫 정정", "둘째 정정").stream()
                    .map(text -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return put(owner, photo.id().toString(), correction(0, UUID.randomUUID(), text));
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(
                    pending.get(0).get(10, TimeUnit.SECONDS), pending.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            var accepted = results.stream()
                    .filter(result -> result.statusCode() == 200)
                    .findFirst()
                    .orElseThrow();
            assertThat(body(get(owner, "")).path("items").get(0)).isEqualTo(body(accepted));
            assertThat(requests.findAll().getFirst().observationId())
                    .isEqualTo(observations.findAll().getFirst().id());
            assertThat(observations.count()).isEqualTo(1);
            assertThat(requests.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("HTTP 파싱 실패와 잘못된 본문은 저장하거나 로그에 노출하지 않는다")
    void malformedAndInvalidJsonNeverLeaksOrWrites() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        root.addAppender(logs);
        try {
            for (String malformed :
                    List.of("{\"observation\":\"" + SECRET + "\",", "{\"summary\":\"" + SECRET + "\" invalid}")) {
                var result = put(owner, photo.id().toString(), malformed);
                assertThat(result.statusCode()).isEqualTo(400);
                assertThat(body(result).path("code").asString()).isEqualTo("VALIDATION_FAILED");
                assertThat(result.body()).doesNotContain(SECRET);
            }
            for (String invalid : List.of(
                    "null",
                    "[]",
                    "{}",
                    correction(0, UUID.randomUUID(), SECRET)
                            .replace("\"expectedRevision\":0", "\"expectedRevision\":0.0"),
                    correction(0, UUID.randomUUID(), SECRET)
                            .replace("\"expectedRevision\":0", "\"expectedRevision\":9223372036854775808"),
                    correction(0, UUID.randomUUID(), SECRET)
                            .replaceFirst("\"requestId\":\"[^\"]+\"", "\"requestId\":\"1-1-1-1-1\""),
                    correction(0, UUID.randomUUID(), SECRET)
                            .replace("\"status\":\"SUCCEEDED\"", "\"status\":\"FAILED\""),
                    correction(0, UUID.randomUUID(), SECRET)
                            .replace("\"mode\":\"ORIGINAL\"", "\"mode\":\"ORIGINAL\",\"injected\":true"),
                    correction(0, UUID.randomUUID(), SECRET)
                            .replace("\"observation\":{", "\"extra\":1,\"observation\":{"))) {
                var result = put(owner, photo.id().toString(), invalid);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
                assertThat(result.body()).doesNotContain(SECRET);
            }
            var dto = MediaObservationCorrectionRequest.from(json.readTree(correction(0, UUID.randomUUID(), SECRET)));
            assertThat(dto.toString()).doesNotContain(SECRET);
            assertThat(observations.count()).isZero();
            assertThat(requests.count()).isZero();
            for (ILoggingEvent log : logs.list) {
                assertThat(log.getFormattedMessage()).doesNotContain(SECRET, "unexpected error");
                for (IThrowableProxy cause = log.getThrowableProxy(); cause != null; cause = cause.getCause()) {
                    assertThat(cause.getMessage()).doesNotContain(SECRET);
                }
            }
        } finally {
            root.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    @DisplayName("HTTP 관찰은 소유권과 전송 상태 삭제 만료 암호화 경계를 지킨다")
    void validatesOwnershipCursorSentStateExpiryDeletionAndCrypto() throws Exception {
        CurrentUser other = user();
        assertThat(get(other, "").statusCode()).isEqualTo(404);
        assertThat(put(other, photo.id().toString(), correction(0, UUID.randomUUID(), "정정"))
                        .statusCode())
                .isEqualTo(404);
        for (String cursor : List.of("0", "x", "999999999999999999999999", "999999")) {
            assertThat(get(owner, "?afterAssetId=" + cursor).statusCode()).isEqualTo(400);
        }
        var pending = attachmentService.upload(
                owner,
                conversation.id(),
                "pending.gif",
                "image/gif",
                IMAGE.length,
                () -> new ByteArrayInputStream(IMAGE));
        assertThat(get(owner, "?afterAssetId=" + pending.id()).statusCode()).isEqualTo(400);
        assertThat(put(owner, pending.id().toString(), correction(0, UUID.randomUUID(), "정정"))
                        .statusCode())
                .isEqualTo(410);
        Object activeKeyId = ReflectionTestUtils.getField(keks, "activeKeyId");
        try {
            ReflectionTestUtils.setField(keks, "activeKeyId", "");
            assertThat(put(owner, photo.id().toString(), correction(0, UUID.randomUUID(), "정정"))
                            .statusCode())
                    .isEqualTo(503);
        } finally {
            ReflectionTestUtils.setField(keks, "activeKeyId", activeKeyId);
        }
        attachmentService.deleteByUser(owner, conversation.id(), photo.id());
        assertThat(put(owner, photo.id().toString(), correction(0, UUID.randomUUID(), "정정"))
                        .statusCode())
                .isEqualTo(410);
        assertThat(body(get(owner, ""))
                        .path("items")
                        .get(0)
                        .path("sourceAssurance")
                        .isNull())
                .isTrue();
        clock.advance(Duration.ofDays(30));
        assertThat(body(get(owner, "")).path("items").get(0).path("observation").isNull())
                .isTrue();
        assertThat(observations.count()).isZero();
    }

    @Test
    @DisplayName("본문이 없으면 assurance는 null이고 stale 본문은 출처를 유지한다")
    void nullBodyTakesPriorityOverProvenanceWhileStaleBodyKeepsAssurance() throws Exception {
        for (boolean user : new boolean[] {false, true}) {
            requests.deleteAll();
            observations.deleteAll();
            Files.write(file(photo), IMAGE);
            var source = user
                    ? new ObservationProvenance(
                            ObservationProvenanceKind.USER_CORRECTION,
                            null,
                            null,
                            null,
                            null,
                            null,
                            1,
                            "media-observation-v1",
                            null)
                    : model();
            var evidence = new MediaObservationInput.Evidence(source.kind(), user ? "USER_CORRECTION" : "123");
            var input = new MediaObservationInput(
                    ObservationStatus.PROCESSING, "합성 본문", List.of(), List.of(), input().coverage(), evidence, null);
            record(0, UUID.randomUUID(), input, source);
            clock.advance(Duration.ofMinutes(15));
            var stale = body(get(owner, "")).path("items").get(0);
            assertThat(stale.path("errorCode").asString()).isEqualTo("ANALYSIS_STALE");
            assertThat(stale.path("sourceAssurance").asString())
                    .isEqualTo(user ? "USER_CORRECTION" : "MODEL_UNVERIFIED");
            var row = observations.findAll().getFirst();
            for (String damaged : List.of("v1.corrupted", "{}", "{\"status\":\"SUCCEEDED\"}")) {
                var sealed = cipher.seal(
                                owner.id(),
                                "media_observation:" + row.id() + ":conversation:" + conversation.id() + ":user:"
                                        + owner.id(),
                                damaged)
                        .orElseThrow();
                jdbc.update(
                        "update media_observation set body=?, body_key_id=? where id=?",
                        sealed.content(),
                        sealed.keyId(),
                        row.id());
                unavailable(body(get(owner, "")).path("items").get(0));
            }
            jdbc.update("update media_observation set body=? where id=?", "v1.corrupted", row.id());
            unavailable(body(get(owner, "")).path("items").get(0));
            bodies.seal(row, json.writeValueAsString(input));
            observations.saveAndFlush(row);
            byte[] changed = IMAGE.clone();
            changed[changed.length - 1]++;
            Files.write(file(photo), changed);
            unavailable(body(get(owner, "")).path("items").get(0));
            clock.set(NOW);
        }
    }

    private static void unavailable(JsonNode item) {
        assertThat(item.path("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(item.path("errorCode").asString()).isEqualTo("CONTENT_UNAVAILABLE");
        assertThat(item.path("observation").isNull()).isTrue();
        assertThat(item.path("provenance").isObject()).isTrue();
        assertThat(item.path("sourceAssurance").isNull()).isTrue();
    }

    private String correction(long revision, UUID id, String summary) {
        return json.writeValueAsString(Map.of(
                "expectedRevision",
                revision,
                "requestId",
                id.toString(),
                "observation",
                Map.of("status", "SUCCEEDED", "summary", summary, "coverage", Map.of("mode", "ORIGINAL"))));
    }

    private HttpResponse<String> put(CurrentUser user, String asset, String body) throws Exception {
        return send(user, "/" + asset, "PUT", body);
    }

    private HttpResponse<String> get(CurrentUser user, String query) throws Exception {
        return send(user, query, "GET", "");
    }

    private HttpResponse<String> send(CurrentUser user, String suffix, String method, String body) throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(user.email())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8)))
                .compact();
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat/conversations/"
                                + conversation.publicId() + "/media-observations" + suffix))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }
}
