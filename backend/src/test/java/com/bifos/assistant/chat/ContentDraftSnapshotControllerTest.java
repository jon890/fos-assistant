package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 원고 snapshot 과 job 의 공개 GET 을 실제 HTTP 인증으로 검사한다. 외부 저장은 시작하지 않는다. */
class ContentDraftSnapshotControllerTest extends ContentDraftJobFixture {
    private static final String[] INTERNAL = {"fileName", "photoDir", "storedName", "payloadFingerprint", "authorization",
        "policyFingerprint", "bindingId", "connectionId", "profile", "ticket", "approvedBy"};
    @Autowired
    ObjectMapper json;


    @Test
    @DisplayName("주인은 snapshot 의 canonical preview 와 포함·빠진 사진 ID 만 읽고 내부 값은 받지 않는다")
    void readsSnapshotPreviewWithoutInternalValues() throws Exception {
        UUID snapshotId = preview();

        var response = get(owner, "/content-drafts/" + draftId + "/snapshots/" + snapshotId);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("snapshotId").stringValue()).isEqualTo(snapshotId.toString());
        assertThat(body.path("revision").longValue()).isEqualTo(1);
        assertThat(body.path("preview").path("title").stringValue()).isEqualTo("비밀 원고");
        assertThat(body.path("assetIds").isArray()).isTrue();
        assertThat(body.path("omittedAssetIds").isArray()).isTrue();
        assertThat(response.body()).doesNotContain(INTERNAL);
        assertThat(starter.started()).isEmpty();
    }


    @Test
    @DisplayName("원고를 고친 뒤의 옛 snapshot 은 본문 없이 409 STALE 이다")
    void returnsStaleWithoutBodyAfterRevision() throws Exception {
        UUID snapshotId = preview();
        drafts.revise(owner, conversation.id(), draftId, 1L, inputDraft("고친 원고", true));

        var response = get(owner, "/content-drafts/" + draftId + "/snapshots/" + snapshotId);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").stringValue()).isEqualTo("CONTENT_DRAFT_STALE");
        assertThat(response.body()).doesNotContain("비밀 원고", "고친 원고", "currentRevision");
    }


    @Test
    @DisplayName("남의 대화·다른 원고 ID·없는 job 은 같은 404 이고 원고 목록도 읽지 못한다")
    void hidesForeignAndMismatchedPathsAsNotFound() throws Exception {
        UUID snapshotId = preview();
        var other = user();
        String path = "/content-drafts/" + draftId + "/snapshots/" + snapshotId;

        assertThat(get(other, path).statusCode()).isEqualTo(404);
        assertThat(get(other, path + "/job").statusCode()).isEqualTo(404);
        assertThat(get(other, "/content-draft-jobs").statusCode()).isEqualTo(404);
        assertThat(get(owner, "/content-drafts/" + UUID.randomUUID() + "/snapshots/" + snapshotId).statusCode()).isEqualTo(404);
        assertThat(get(owner, path + "/job").statusCode()).isEqualTo(404);
        assertThat(get(owner, "/content-draft-jobs?beforeSnapshotId=" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get(owner, "/content-draft-jobs?beforeSnapshotId=NOT-A-UUID").statusCode()).isEqualTo(400);
    }


    @Test
    @DisplayName("저장 요청 뒤 job 과 목록은 보관한 상태만 내고 원격 조회와 시작을 하지 않는다")
    void readsStoredJobAndPageWithoutRemoteCalls() throws Exception {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);

        var job = get(owner, "/content-drafts/" + draftId + "/snapshots/" + snapshotId + "/job");
        var page = get(owner, "/content-draft-jobs");

        assertThat(job.statusCode()).isEqualTo(200);
        JsonNode view = json.readTree(job.body());
        assertThat(view.path("actionId").stringValue()).isEqualTo(request.actionId().toString());
        assertThat(view.path("status").stringValue()).isEqualTo("PREVIEW_READY");
        assertThat(view.path("jobId").isNull()).isTrue();
        assertThat(page.statusCode()).isEqualTo(200);
        JsonNode items = json.readTree(page.body()).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.path(0).path("snapshotId").stringValue()).isEqualTo(snapshotId.toString());
        assertThat(json.readTree(page.body()).path("nextBeforeSnapshotId").isNull()).isTrue();
        assertThat(job.body() + page.body()).doesNotContain(INTERNAL).doesNotContain("비밀 원고");
        verify(compiler, never()).status(any(), any(), any());
        assertThat(starter.started()).isEmpty();
    }


    @Test
    @DisplayName("승인 카드 목록은 원고 줄에만 원래 대화와 snapshot 요약을 붙인다")
    void attachesContentDraftSummaryToApprovalLine() throws Exception {
        UUID snapshotId = preview();
        saves.requestSave(owner, origin.id(), snapshotId);

        var response = HttpClient.newHttpClient().send(request(owner, "/api/v1/chat/conversations/" + conversation.publicId()
            + "/connector-actions"), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode summary = json.readTree(response.body()).path(0).path("contentDraft");
        assertThat(summary.path("snapshotId").stringValue()).isEqualTo(snapshotId.toString());
        assertThat(summary.path("draftId").stringValue()).isEqualTo(draftId.toString());
        assertThat(summary.path("conversationId").stringValue()).isEqualTo(conversation.publicId().toString());
        assertThat(summary.path("status").stringValue()).isEqualTo("PREVIEW_READY");
        assertThat(summary.toString()).doesNotContain(INTERNAL);
    }


    @Test
    @DisplayName("승인·거절 응답도 원고 줄에 요약을 붙여 시작 지원이 없을 때의 실행 없는 거절과 사용자 거절을 구분한다")
    void attachesSummaryToApproveAndRejectResponses() throws Exception {
        catalog(false);
        var refused = saves.requestSave(owner, origin.id(), preview());

        JsonNode approved = json.readTree(post(owner, "/api/v1/connector-actions/" + refused.actionId() + "/approve").body());

        assertThat(approved.path("status").stringValue()).isEqualTo("REJECTED");
        assertThat(approved.path("errorCode").stringValue()).isEqualTo("not_executable");
        assertThat(approved.path("contentDraft").path("status").stringValue()).isEqualTo("STALE");
        assertThat(approved.path("contentDraft").path("errorCode").stringValue()).isEqualTo("not_executable");
        assertThat(approved.path("contentDraft").path("conversationId").stringValue()).isEqualTo(conversation.publicId().toString());
        catalog(true);
        drafts.revise(owner, conversation.id(), draftId, 1L, inputDraft("거절할 원고", true));
        var declined = saves.requestSave(owner, origin.id(),
            snapshots.preview(owner, conversation.id(), draftId, 2, binding.id(), origin.id()).snapshotId());

        JsonNode rejected = json.readTree(post(owner, "/api/v1/connector-actions/" + declined.actionId() + "/reject").body());

        assertThat(rejected.path("status").stringValue()).isEqualTo("REJECTED");
        assertThat(rejected.path("contentDraft").path("status").stringValue()).isEqualTo("STALE");
        assertThat(rejected.path("contentDraft").path("errorCode").isNull()).isTrue();
        assertThat(approved.toString() + rejected).doesNotContain(INTERNAL);
        assertThat(starter.started()).isEmpty();
    }


    @Test
    @DisplayName("시작을 접수한 승인 응답은 진행 중인 원고 요약을 싣는다")
    void attachesRunningSummaryToAcceptedApproval() throws Exception {
        var request = saves.requestSave(owner, origin.id(), preview());

        JsonNode approved = json.readTree(post(owner, "/api/v1/connector-actions/" + request.actionId() + "/approve").body());

        assertThat(approved.path("status").stringValue()).isEqualTo("EXECUTING");
        assertThat(approved.path("contentDraft").path("status").stringValue()).isIn("DISPATCHED", "SAVING");
        assertThat(starter.started()).hasSize(1);
    }


    private HttpResponse<String> post(CurrentUser user, String path) throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder().subject(user.email()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8))).compact();
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }


    private HttpResponse<String> get(CurrentUser user, String suffix) throws Exception {
        return HttpClient.newHttpClient().send(request(user, "/api/v1/chat/conversations/" + conversation.publicId() + suffix),
            HttpResponse.BodyHandlers.ofString());
    }


    private HttpRequest request(CurrentUser user, String path) {
        Instant now = Instant.now();
        String token = Jwts.builder().subject(user.email()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8))).compact();
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + token)
            .GET().build();
    }
}
