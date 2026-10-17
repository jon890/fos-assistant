package com.bifos.assistant.chat;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.contentdraft.application.ContentDraftInputReader;
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
import tools.jackson.databind.node.ObjectNode;

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


    @Test
    @DisplayName("짝 없는 surrogate는 POST와 PUT에서 거절하고 기존 원고와 목록을 보존한다")
    void rejectsUnpairedSurrogatesWithoutChangingStoredRevisions() throws Exception {
        var id = drafts.create(owner, conversation.id(), inputDraft("보존", false)).draft().draftId();
        var stored = revisionRows.findAll().getFirst();
        for (String escaped : List.of("\\ud800", "\\udc00", "\\ud800x", "x\\udc00", "\\ud800\\ud800", "\\udc00\\ud800")) {
            for (String field : List.of("title", "category", "tag", "text", "caption", "name", "address", "type", "code", "assetId", "observationId")) {
                String malformed = json.writeValueAsString(unicodeDraft(field, "UNICODE_VALUE"))
                    .replace("UNICODE_VALUE", escaped);
                var post = send(owner, "", "POST", malformed);
                assertThat(post.statusCode()).as("POST %s %s", field, escaped).isEqualTo(400);
                assertThat(post.body()).contains("VALIDATION_FAILED");
                var put = send(owner, "/" + id, "PUT", "{\"expectedRevision\":1,\"draft\":" + malformed + "}");
                assertThat(put.statusCode()).as("PUT %s %s", field, escaped).isEqualTo(400);
                assertThat(put.body()).contains("VALIDATION_FAILED");
            }
        }
        String duplicates = json.writeValueAsString(inputDraft("태그", false))
            .replace("\"tags\":[]", "\"tags\":[\"x\\ud800\",\"x\\udc00\"]");
        assertThat(send(owner, "", "POST", duplicates).statusCode()).isEqualTo(400);
        assertThat(send(owner, "/" + id, "PUT", "{\"expectedRevision\":1,\"draft\":" + duplicates + "}").statusCode()).isEqualTo(400);
        assertThat(draftRows.count()).isEqualTo(1);
        assertThat(revisionRows.count()).isEqualTo(1);
        assertThat(draftRows.findAll().getFirst().currentRevision()).isEqualTo(1);
        var unchanged = revisionRows.findById(stored.id()).orElseThrow();
        assertThat(unchanged.body()).isEqualTo(stored.body());
        assertThat(unchanged.bodyKeyId()).isEqualTo(stored.bodyKeyId());
        assertThat(send(owner, "/" + id, "GET", "").statusCode()).isEqualTo(200);
        var list = send(owner, "", "GET", "");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json.readTree(list.body()).path("items").get(0).path("title").asString()).isEqualTo("보존");
    }


    @Test
    @DisplayName("유효한 surrogate pair와 비BMP 문자는 HTTP 생성 수정 이전 판 조회에서 그대로 보존한다")
    void preservesSupplementaryUnicodeAcrossHttpRevisions() throws Exception {
        String value = "사진 😀 𐐀";
        var node = unicodeDraft("title", value);
        node.put("category", value);
        node.set("tags", json.createArrayNode().add(value));
        node.set("blocks", json.createArrayNode()
            .add(json.createObjectNode().put("type", "paragraph").put("text", value))
            .add(json.createObjectNode().put("type", "image").put("assetId", photo.id().toString()).put("caption", value))
            .add(json.createObjectNode().put("type", "map").put("name", value).put("address", value)));
        var created = send(owner, "", "POST", json.writeValueAsString(node));
        assertThat(created.statusCode()).isEqualTo(201);
        var first = json.readTree(created.body()).path("draft");
        assertThat(first.path("title").asString()).isEqualTo(value);
        assertThat(first.path("category").asString()).isEqualTo(value);
        assertThat(first.path("tags").get(0).asString()).isEqualTo(value);
        assertThat(first.path("blocks")).isEqualTo(node.path("blocks"));
        String id = first.path("draftId").asString();
        node.put("title", value + " 수정");
        var revised = send(owner, "/" + id, "PUT", "{\"expectedRevision\":1,\"draft\":" + json.writeValueAsString(node) + "}");
        assertThat(revised.statusCode()).isEqualTo(200);
        assertThat(json.readTree(revised.body()).path("draft").path("title").asString()).isEqualTo(value + " 수정");
        assertThat(json.readTree(send(owner, "/" + id + "/revisions/1", "GET", "").body()).path("draft")).isEqualTo(first);
    }


    @Test
    @DisplayName("HTTP 생성과 수정은 같은 사진의 반복을 고정 코드와 안내로 거절하고 서로 다른 사진은 저장한다")
    void rejectsDuplicatePhotoOnHttpCreateAndRevise() throws Exception {
        var repeated = (ObjectNode)json.valueToTree(inputDraft("반복 사진", true));
        ((ObjectNode)repeated.path("blocks").get(1)).put("assetId", photo.id().toString());
        var post = send(owner, "", "POST", json.writeValueAsString(repeated));
        assertThat(post.statusCode()).isEqualTo(400);
        assertThat(json.readTree(post.body()).path("code").asString()).isEqualTo("CONTENT_DRAFT_DUPLICATE_PHOTO");
        assertThat(json.readTree(post.body()).path("message").asString()).isEqualTo(ContentDraftInputReader.DUPLICATE_PHOTO_MESSAGE);
        assertThat(draftRows.count()).isZero();

        var created = send(owner, "", "POST", json.writeValueAsString(inputDraft("서로 다른 사진", true)));
        assertThat(created.statusCode()).isEqualTo(201);
        var id = json.readTree(created.body()).path("draft").path("draftId").asString();
        var put = send(owner, "/" + id, "PUT", "{\"expectedRevision\":1,\"draft\":" + json.writeValueAsString(repeated) + "}");
        assertThat(put.statusCode()).isEqualTo(400);
        assertThat(json.readTree(put.body()).path("code").asString()).isEqualTo("CONTENT_DRAFT_DUPLICATE_PHOTO");
        assertThat(revisionRows.count()).isEqualTo(1);
        var distinct = json.createObjectNode().put("expectedRevision", 1);
        distinct.set("draft", json.valueToTree(inputDraft("다시 다른 사진", true)));
        assertThat(send(owner, "/" + id, "PUT", json.writeValueAsString(distinct)).statusCode()).isEqualTo(200);
    }


    private ObjectNode unicodeDraft(String field, String value) {
        var node = (ObjectNode)json.valueToTree(inputDraft("원고", false));
        var blocks = json.createArrayNode();
        node.set("blocks", blocks);
        switch (field) {
            case "title", "category" -> node.put(field, value);
            case "tag" -> node.set("tags", json.createArrayNode().add(value));
            case "text" -> blocks.add(json.createObjectNode().put("type", "paragraph").put("text", value));
            case "caption", "assetId" -> {
                var image = json.createObjectNode().put("type", "image").put("assetId", photo.id().toString());
                image.put(field, value);
                blocks.add(image);
            }
            case "observationId" -> {
                var image = json.createObjectNode().put("type", "image").put("assetId", photo.id().toString());
                image.set("observationRef", json.createObjectNode().put("id", value).put("revision", 1));
                blocks.add(image);
            }
            case "name", "address" -> blocks.add(json.createObjectNode().put("type", "map").put("name", "장소").put("address", "주소").put(field, value));
            case "type" -> blocks.add(json.createObjectNode().put("type", value).put("text", "본문"));
            case "code" -> blocks.add(json.createObjectNode().put("type", "sticker").put("code", value));
            default -> throw new IllegalArgumentException(field);
        }
        return node;
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
