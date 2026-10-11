package com.bifos.assistant.chat;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.shared.auth.CurrentUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class ContentDraftControllerTest extends ContentDraftFixture {
    @LocalServerPort
    int port;

    @Test
    @DisplayName("HTTP 생성 이전 조회 목록과 typed 충돌을 반환하고 본문은 숨긴다")
    void servesRestRevisionsAndTypedConflictWithoutBody() throws Exception {
        assertThat(json.readTree(send(owner, "", "GET", "").body()).path("items").size()).isZero();
        var first = send(owner, "", "POST", json.writeValueAsString(inputDraft("HTTP 원고", false)));
        assertThat(first.statusCode()).isEqualTo(201);
        var id = json.readTree(first.body()).path("draft").path("draftId").asString();
        var request = json.createObjectNode().put("expectedRevision", 1);
        request.set("draft", json.valueToTree(inputDraft("수정 원고", false)));
        assertThat(send(owner, "/" + id, "PUT", json.writeValueAsString(request)).statusCode()).isEqualTo(200);
        var conflict = send(owner, "/" + id, "PUT", json.writeValueAsString(request));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).path("currentRevision").asLong()).isEqualTo(2);
        assertThat(conflict.body()).doesNotContain("HTTP 원고", "수정 원고");
        assertThat(send(owner, "/" + id + "/revisions/1", "GET", "").body()).contains("HTTP 원고");
        assertThat(send(user(), "/" + id, "GET", "").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("중복 JSON 키 malformed null unknown과 revision coercion을 저장 없이 거절한다")
    void rejectsMalformedDuplicateKeysAndCoercionWithoutWrites() throws Exception {
        String base = json.writeValueAsString(inputDraft("보존", false));
        var response = send(owner, "", "POST", base.replace("\"title\":\"보존\"", "\"title\":\"첫\",\"title\":\"둘\""));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(send(owner, "", "POST", "{\"title\":").statusCode()).isEqualTo(400);
        var id = drafts.create(owner, conversation.id(), inputDraft("보존", false)).draft().draftId();
        for (String revision : List.of("\"1\"", "1.0", "null", "9007199254740992")) {
            assertThat(send(owner, "/" + id, "PUT", "{\"expectedRevision\":" + revision + ",\"draft\":" + base + "}").statusCode()).isEqualTo(400);
        }
        assertThat(send(owner, "/" + id + "/revisions/1.0", "GET", "").statusCode()).isEqualTo(400);
        assertThat(send(owner, "/" + id.toString().toUpperCase(), "GET", "").statusCode()).isEqualTo(400);
        assertThat(revisionRows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP STALE와 만료에는 본문 없이 현재 번호와 안전한 코드만 준다")
    void returnsStaleRevisionAndExpiryWithoutContent() throws Exception {
        var id = drafts.create(owner, conversation.id(), inputDraft("사진 비밀", true)).draft().draftId();
        attachmentCleaner.delete(photo, NOW);
        var stale = send(owner, "/" + id, "GET", "");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json.readTree(stale.body()).path("currentRevision").asLong()).isEqualTo(1);
        assertThat(stale.body()).doesNotContain("사진 비밀");
        clock.set(draftRows.findAll().getFirst().expiresAt());
        assertThat(send(owner, "/" + id, "GET", "").statusCode()).isEqualTo(410);
    }

    private HttpResponse<String> send(CurrentUser user, String suffix, String method, String body) throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder().subject(user.email()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8))).compact();
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/v1/chat/conversations/" + conversation.publicId() + "/content-drafts" + suffix))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
