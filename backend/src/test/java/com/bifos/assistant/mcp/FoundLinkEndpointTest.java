package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FakeLinkImageWeb;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 비서가 찾은 주소를 알리는 경로와, 그 주소를 그림 바탕으로 받는 경로를 함께 확인한다(스펙 agent-found-image REQ2, REQ3, REQ8).
 *
 * <p>본문은 운영 코드가 아니라 이 테스트가 {@code hermes/plugins/fos-ctx/README.md} 의 「찾은 주소를 알린다」 대로 따로 만든다.
 */
@BackendIntegrationTest
class FoundLinkEndpointTest {
    private static final String REPORT = "/internal/hermes/found-links";
    private static final String SOURCE = "/internal/hermes/image-source";
    private static final String PROFILE = "found-link-endpoint";

    @LocalServerPort
    int port;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    HermesSessionBindingRepository bindings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeLinkImageWeb web;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private CurrentUser user;
    private Conversation conversation;
    private AgentExecution execution;
    private String token;
    private String root;


    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        user = member();
        conversation = conversation(user);
        token = tokens.issue(PROFILE, "검사").rawToken();
        root = McpCallSigner.newRoot();
        execution = run(user, conversation, root);
    }


    @Test
    @DisplayName("알린 페이지 주소의 대표 사진을 받고 알리지 않았거나 글자를 바꾼 주소는 바깥에 요청하지 않고 거절한다")
    void fetchesOnlyReportedLinks() throws Exception {
        byte[] png = png(7);
        web.html("https://shop.example.com/goods/1", "<meta property=\"og:image\" content=\"https://img.example.com/p.png\">");
        web.image("https://img.example.com/p.png", "image/png", png);

        assertThat(report(root, page(null, "https://shop.example.com/goods/1")).statusCode()).isEqualTo(204);

        HttpResponse<byte[]> found = source(root, "link:https://shop.example.com/goods/1");
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).isEqualTo(png);
        assertThat(found.headers().firstValue("x-image-source-link")).contains("https://shop.example.com/goods/1");
        assertThat(found.headers().firstValue("x-image-source-found")).as("비서가 찾은 주소로 받았다").contains("1");
        web.reset();
        for (String link : List.of("https://shop.example.com/goods/2", "https://shop.example.com/goods/1?x=secret", "https://img.example.com/p.png")) {
            HttpResponse<byte[]> response = source(root, "link:" + link);
            assertThat(response.statusCode()).as(link).isEqualTo(422);
            assertThat(code(response)).as(link).isEqualTo("IMAGE_SOURCE_UNKNOWN");
        }
        assertThat(web.requested()).isEmpty();
    }


    @Test
    @DisplayName("상품 페이지가 다시 열리지 않아도 이미 읽은 상품 사진을 받고 그 주소로 다음 장면을 그릴 수 있다")
    void selectsProductImageFromPreviouslyReadPage() throws Exception {
        List<Integer> statuses = List.of(301, 403, 202, 500);
        for (int status : statuses) {
            String requested = "https://shop.example.com/products/" + status;
            String finalPage = "https://shop.example.com/products/final-" + status;
            String photo = "https://cdn.example.com/item/" + status + ".png";
            web.status(requested, status);
            web.image(photo, "image/png", png(status));
            assertThat(report(root, page(null, requested)).statusCode()).isEqualTo(204);
            reportPhotos(requested, finalPage,
                new Photo("/common/og.png", "og", 0),
                new Photo("/item/large.png", "body", 1_000_000),
                new Photo(photo, "product", 0));

            HttpResponse<byte[]> image = source(root, "link:" + requested);
            assertThat(image.statusCode()).as(requested).isEqualTo(200);
            assertThat(image.body()).isEqualTo(png(status));
            assertThat(image.headers().firstValue("x-image-source-link")).contains(photo);
            assertThat(image.headers().firstValue("x-image-source-found")).contains("1");
        }
        assertThat(web.requested()).containsExactly(
            "cdn.example.com/item/301.png", "cdn.example.com/item/403.png", "cdn.example.com/item/202.png", "cdn.example.com/item/500.png");

        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", execution.id());
        String nextRoot = McpCallSigner.newRoot();
        run(user, conversation, nextRoot);
        assertThat(source(nextRoot, "link:https://cdn.example.com/item/403.png").body()).isEqualTo(png(403));
        web.html("https://cdn.example.com/item/403.png", "<meta property=\"og:image\" content=\"https://cdn.example.com/changed.png\">");
        web.image("https://cdn.example.com/changed.png", "image/png", png(1));
        assertThat(code(source(nextRoot, "link:https://cdn.example.com/item/403.png"))).isEqualTo("IMAGE_SOURCE_LINK_NO_IMAGE");
        assertThat(web.requested()).doesNotContain("cdn.example.com/changed.png");
    }


    @Test
    @DisplayName("상품용 Open Graph 사진이 있으면 쓰고 사이트 공통 사진이면 가장 큰 본문 사진을 쓴다")
    void skipsCommonOpenGraphAndSelectsLargestBodyImage() throws Exception {
        String first = "https://shop.example.com/goods/1";
        String second = "https://shop.example.com/goods/2";
        String third = "https://shop.example.com/goods/3";
        String unique = "https://cdn.example.com/og/unique.png";
        String shared = "https://cdn.example.com/og/repeated.png";
        String largest = "https://cdn.example.com/item/large.png";
        web.image(unique, "image/png", png(9));
        web.image(largest, "image/png", png(10));
        assertThat(report(root, page(null, first, second, third)).statusCode()).isEqualTo(204);
        reportPhotos(first, first, new Photo(unique, "og", 0), new Photo(largest, "body", 500_000));
        reportPhotos(second, second,
            new Photo("/assets/all_og_store.jpg", "og", 0), new Photo(shared, "og", 0),
            new Photo("/small.jpg", "body", 2_000), new Photo(largest, "body", 500_000));
        reportPhotos(third, third, new Photo(shared, "og", 0));
        assertThat(report(root, page(first, "/item/from-ordinary-read.png")).statusCode()).isEqualTo(204);

        assertThat(source(root, "link:" + first).body()).isEqualTo(png(9));
        assertThat(source(root, "link:" + second).body()).isEqualTo(png(10));
        assertFailure("link:" + third, 422, "IMAGE_SOURCE_LINK_NO_IMAGE");
        assertThat(web.requested()).containsExactly("cdn.example.com/og/unique.png", "cdn.example.com/item/large.png");
    }


    @Test
    @DisplayName("읽은 주소 자체는 발견 기록이 아니고 선택한 사진도 그 페이지의 후보와 대화 기록에 있어야 한다")
    void cannotAuthorizePageOrAnUnreportedPhotoByReadingIt() throws Exception {
        String requested = "https://shop.example.com/goods/1";
        String photo = "https://cdn.example.com/image.png";
        reportPhotos(requested, requested, new Photo(photo, "product", 0));
        assertFailure("link:" + requested, 422, "IMAGE_SOURCE_UNKNOWN");
        String otherRoot = McpCallSigner.newRoot();
        run(user, conversation(user), otherRoot);
        assertThat(report(otherRoot, page(null, requested)).statusCode()).isEqualTo(204);
        assertThat(code(source(otherRoot, "link:" + photo))).isEqualTo("IMAGE_SOURCE_UNKNOWN");

        ObjectNode read = page(requested);
        read.putArray("images").addObject().put("url", photo).put("kind", "product").put("size", 0);
        assertThat(report(root, page(null, requested)).statusCode()).isEqualTo(204);
        assertThat(report(root, read).statusCode()).isEqualTo(204);
        assertFailure("link:" + requested, 422, "IMAGE_SOURCE_LINK_NO_IMAGE");
        assertThat(web.requested()).isEmpty();
    }


    @Test
    @DisplayName("읽은 본문의 사진 주소는 받고 읽기 도구에 적어 넣은 페이지 주소 자체는 받지 않는다")
    void acceptsBodyLinksButNotThePageItself() throws Exception {
        web.image("https://shop.example.com/img/a.png", "image/png", png(3));

        assertThat(report(root, page("https://shop.example.com/typed", "/img/a.png", "https://shop.example.com/typed")).statusCode())
            .isEqualTo(204);

        assertThat(source(root, "link:https://shop.example.com/img/a.png").statusCode()).isEqualTo(200);
        HttpResponse<byte[]> typed = source(root, "link:https://shop.example.com/typed");
        assertThat(typed.statusCode()).isEqualTo(422);
        assertThat(code(typed)).isEqualTo("IMAGE_SOURCE_UNKNOWN");
    }


    @Test
    @DisplayName("읽기 서비스가 넘어간 주소를 돌려줘도 함께 알린 적어 넣은 주소는 받지 않는다")
    void excludesTypedUrlsSentWithTheReport() throws Exception {
        ObjectNode body = body(
            McpCallSigner.context(token, "found_links", root),
            page("https://final.example.com/landed", "https://typed.example.com/go", "https://final.example.com/a.png"));
        body.putArray("typed").add("https://typed.example.com/go");
        web.image("https://final.example.com/a.png", "image/png", png(6));

        assertThat(post(REPORT, body, token).statusCode()).isEqualTo(204);

        assertThat(code(source(root, "link:https://typed.example.com/go"))).isEqualTo("IMAGE_SOURCE_UNKNOWN");
        assertThat(source(root, "link:https://final.example.com/a.png").statusCode()).isEqualTo(200);
    }


    @Test
    @DisplayName("다른 대화와 다른 사용자의 실행이 알린 주소는 422 IMAGE_SOURCE_UNKNOWN 이고 바깥에 요청하지 않는다")
    void rejectsLinksFoundElsewhere() throws Exception {
        String otherRoot = McpCallSigner.newRoot();
        run(user, conversation(user), otherRoot);
        CurrentUser stranger = member();
        String strangerRoot = McpCallSigner.newRoot();
        run(stranger, conversation(stranger), strangerRoot);
        assertThat(report(otherRoot, page(null, "https://shop.example.com/elsewhere")).statusCode()).isEqualTo(204);
        assertThat(report(strangerRoot, page(null, "https://shop.example.com/stranger")).statusCode()).isEqualTo(204);

        for (String link : List.of("elsewhere", "stranger")) {
            HttpResponse<byte[]> response = source(root, "link:https://shop.example.com/" + link);
            assertThat(response.statusCode()).as(link).isEqualTo(422);
            assertThat(code(response)).as(link).isEqualTo("IMAGE_SOURCE_UNKNOWN");
        }
        assertThat(web.requested()).isEmpty();
    }


    @Test
    @DisplayName("앞 답의 실행이 알린 주소를 같은 대화의 다음 실행이 받는다")
    void laterRunInTheSameConversationFetchesEarlierLinks() throws Exception {
        web.image("https://img.example.com/earlier.png", "image/png", png(4));
        assertThat(report(root, page(null, "https://img.example.com/earlier.png")).statusCode()).isEqualTo(204);
        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", execution.id());
        String nextRoot = McpCallSigner.newRoot();
        run(user, conversation, nextRoot);

        assertThat(source(nextRoot, "link:https://img.example.com/earlier.png").statusCode()).isEqualTo(200);
    }


    @Test
    @DisplayName("서명이 틀리거나 토큰이 없는 알리기는 거절되고 남지 않는다")
    void rejectsUnsignedReports() throws Exception {
        ObjectNode wrongTool = body(McpCallSigner.context(token, "image_generate", root), page(null, "https://shop.example.com/forged"));
        assertThat(post(REPORT, wrongTool, token).statusCode()).isEqualTo(403);
        ObjectNode signed = body(McpCallSigner.context(token, "found_links", root), page(null, "https://shop.example.com/forged"));
        assertThat(post(REPORT, signed, null).statusCode()).isIn(401, 403);

        assertThat(conversations.findFoundLinkHashes(conversation.id())).isEmpty();
        assertThat(code(source(root, "link:https://shop.example.com/forged"))).isEqualTo("IMAGE_SOURCE_UNKNOWN");
    }


    @Test
    @DisplayName("후보가 상한을 넘거나 본문 모양이 틀리면 400 이고 남지 않는다")
    void rejectsMalformedReports() throws Exception {
        String[] many = new String[2_001];
        Arrays.setAll(many, index -> "https://shop.example.com/" + index);
        assertThat(report(root, page(null, many)).statusCode()).isEqualTo(400);
        ObjectNode noPages = json.createObjectNode();
        noPages.set("_fos_ctx", McpCallSigner.context(token, "found_links", root));
        assertThat(post(REPORT, noPages, token).statusCode()).isEqualTo(400);
        assertThat(report(root, page("https://shop.example.com/" + "x".repeat(2_048), "/a.png")).statusCode()).isEqualTo(400);
        ObjectNode manyTyped = body(McpCallSigner.context(token, "found_links", root), page(null, "https://shop.example.com/ok"));
        ArrayNode typed = manyTyped.putArray("typed");
        for (int index = 0; index < 51; index++) {
            typed.add("https://shop.example.com/" + index);
        }
        assertThat(post(REPORT, manyTyped, token).statusCode()).isEqualTo(400);
        ObjectNode numbers = page(null);
        ((ArrayNode)numbers.get("links")).add(1);
        assertThat(report(root, numbers).statusCode()).isEqualTo(400);

        assertThat(conversations.findFoundLinkHashes(conversation.id())).isEmpty();
    }


    @Test
    @DisplayName("찾은 주소도 받지 못한 까닭마다 사용자가 보낸 링크와 같은 오류 코드를 낸다")
    void appliesTheSameFetchChecks() throws Exception {
        web.html("https://shop.example.com/plain", "<title>사진 없음</title>");
        byte[] huge = new byte[20 * 1024 * 1024 + 1];
        System.arraycopy(png(1), 0, huge, 0, 8);
        web.image("https://img.example.com/huge.png", "image/png", huge);
        web.status("https://shop.example.com/gone", 404);
        report(root, page(
            null,
            "https://10.0.0.5/inner.png",
            "https://shop.example.com:8443/port",
            "https://shop.example.com/plain",
            "https://img.example.com/huge.png",
            "https://shop.example.com/gone"));

        assertFailure("link:https://10.0.0.5/inner.png", 422, "IMAGE_SOURCE_LINK_BLOCKED");
        assertThat(web.requested()).as("내부망 꼴의 주소로는 바깥에 요청하지 않는다").isEmpty();
        assertFailure("link:https://shop.example.com:8443/port", 422, "IMAGE_SOURCE_LINK_BLOCKED");
        assertFailure("link:https://shop.example.com/plain", 422, "IMAGE_SOURCE_LINK_NO_IMAGE");
        assertFailure("link:https://img.example.com/huge.png", 422, "IMAGE_SOURCE_LINK_TOO_LARGE");
        assertFailure("link:https://shop.example.com/gone", 502, "IMAGE_SOURCE_LINK_FAILED");
    }


    @Test
    @DisplayName("찾은 주소를 그림에 남는 꼴로 다시 가리켜도 받는다")
    void refetchesStoredFormOfFoundLink() throws Exception {
        String stored = "https://xn--hp4bo76a.example.com/%EC%83%81%ED%92%88/1?a=1&b=2";
        web.image(stored, "image/png", png(9));
        report(root, page("https://shop.example.com/list", "https://쇼핑.example.com/상품/1?a=1&amp;b=2"));

        HttpResponse<byte[]> first = source(root, "link:https://쇼핑.example.com/상품/1?a=1&amp;b=2");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("x-image-source-link")).as("그림에 남기는 꼴").contains(stored);

        assertThat(source(root, "link:" + stored).statusCode()).as("다음 장면에서 다시 받는다").isEqualTo(200);
    }


    @Test
    @DisplayName("맡긴 실행이 알린 주소는 부모 대화에 남고 대화 없는 실행의 알리기는 남기지 않고 204 다")
    void recordsUnderTheOriginConversation() throws Exception {
        web.image("https://img.example.com/child.png", "image/png", png(5));
        String child = "child-" + UUID.randomUUID();
        bindings.saveAndFlush(HermesSessionBinding.of(PROFILE, child, execution, root, root));
        ObjectNode fromChild = body(
            McpCallSigner.context(token, "found_links", root, child, "call_child"), page(null, "https://img.example.com/child.png"));
        assertThat(post(REPORT, fromChild, token).statusCode()).isEqualTo(204);
        assertThat(source(root, "link:https://img.example.com/child.png").statusCode()).isEqualTo(200);

        String loose = McpCallSigner.newRoot();
        executions.save(AgentExecution.builder()
            .userId(user.id())
            .profileName(PROFILE)
            .hermesSessionId(loose)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.now().minusSeconds(1))
            .build());
        assertThat(report(loose, page(null, "https://img.example.com/loose.png")).statusCode()).isEqualTo(204);
    }


    private void assertFailure(String source, int status, String code) throws Exception {
        HttpResponse<byte[]> response = source(root, source);
        assertThat(response.statusCode()).as(source).isEqualTo(status);
        assertThat(code(response)).as(source).isEqualTo(code);
    }


    private CurrentUser member() {
        AppUser saved = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "사용자", 1L, UserRole.MEMBER));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), 1L, UserRole.MEMBER);
    }


    private Conversation conversation(CurrentUser owner) {
        return conversations.save(Conversation.startedBy(owner.id(), "찾은 사진", null, Instant.now()));
    }


    private AgentExecution run(CurrentUser owner, Conversation of, String session) {
        return executions.save(AgentExecution.builder()
            .userId(owner.id())
            .conversationId(of.id())
            .profileName(PROFILE)
            .hermesSessionId(session)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.now().minusSeconds(1))
            .build());
    }


    private ObjectNode page(String base, String... links) {
        ObjectNode page = json.createObjectNode();
        if (base == null) {
            page.putNull("base");
        } else {
            page.put("base", base);
        }
        ArrayNode values = page.putArray("links");
        for (String link : links) {
            values.add(link);
        }
        return page;
    }


    private ObjectNode body(ObjectNode context, ObjectNode page) {
        ObjectNode body = json.createObjectNode();
        body.set("_fos_ctx", context);
        body.putArray("pages").add(page);
        return body;
    }


    private HttpResponse<byte[]> report(String session, ObjectNode page) throws Exception {
        return post(REPORT, body(McpCallSigner.context(token, "found_links", session), page), token);
    }


    private void reportPhotos(String requested, String base, Photo... photos) throws Exception {
        ObjectNode page = page(base, Arrays.stream(photos).map(Photo::url).toArray(String[]::new));
        page.put("requested", requested);
        ArrayNode images = page.putArray("images");
        for (Photo photo : photos) {
            images.addObject().put("url", photo.url()).put("kind", photo.kind()).put("size", photo.size());
        }
        ObjectNode body = body(McpCallSigner.context(token, "found_links", root), page);
        body.putArray("typed").add(requested);
        assertThat(post(REPORT, body, token).statusCode()).isEqualTo(204);
    }


    private record Photo(String url, String kind, long size) {
    }


    private HttpResponse<byte[]> source(String session, String source) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.set("_fos_ctx", McpCallSigner.context(token, "image_generate", session));
        body.put("source", source);
        return post(SOURCE, body, token);
    }


    private HttpResponse<byte[]> post(String path, ObjectNode body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }


    private String code(HttpResponse<byte[]> response) {
        return json.readTree(new String(response.body(), StandardCharsets.UTF_8)).path("code").asString();
    }


    /** PNG 머리 바이트 뒤에 `filler` 값을 채운 테스트용 그림이다. */
    private static byte[] png(int filler) {
        byte[] bytes = new byte[128];
        Arrays.fill(bytes, (byte)filler);
        byte[] magic = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }
}
