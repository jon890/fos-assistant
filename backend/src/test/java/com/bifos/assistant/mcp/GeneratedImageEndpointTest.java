package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 비서가 만든 그림을 올리는 경로의 인증, 저장, 거절을 확인한다(ADR-20261119 / generated-image).
 *
 * <p>본문과 서명은 운영 코드가 아니라 이 테스트가 {@code hermes/plugins/fos-ctx/README.md} 의 「만든 그림을 올린다」 대로 따로 만든다.
 */
@BackendIntegrationTest
class GeneratedImageEndpointTest {
    private static final String PATH = "/internal/hermes/generated-image";
    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String PROFILE = "generated-image-main";
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    /** `hermes/tests/test_generated_image.py` 의 `VECTOR_SIG` 와 같은 확인 값이다. */
    private static final String VECTOR_SIG = "d5c850a5926fcf396a1e87367d88573994150da200a6ce78a4270388394d69ee";
    private static final byte[] PNG = png(64);

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    HermesSessionBindingRepository bindings;

    @Autowired
    ChatArtifactRepository rows;

    @Autowired
    ArtifactStore store;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> folders = new ArrayList<>();
    private String token;
    private AppUser owner;
    private Agent agent;


    @BeforeEach
    void setUp() {
        String ofProfile = "(SELECT id FROM agent WHERE hermes_profile = ?)";
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM chat_artifact WHERE conversation_id IN (SELECT id FROM conversation WHERE agent_id IN " + ofProfile + ")", PROFILE);
        jdbc.update("DELETE FROM conversation WHERE agent_id IN " + ofProfile, PROFILE);
        jdbc.update("DELETE FROM agent WHERE hermes_profile = ?", PROFILE);
        jdbc.update("DELETE FROM agent_token WHERE profile_name = ?", PROFILE);
        owner = users.save(AppUser.of("image-owner-" + UUID.randomUUID() + "@example.com", "주인", 1L, UserRole.MEMBER));
        agent = agents.save(Agent.of(
            "bifos-" + UUID.randomUUID(),
            "메인 에이전트",
            PROFILE,
            "http://localhost",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
        token = tokens.issue(PROFILE, "main").rawToken();
    }


    @AfterEach
    void tearDown() {
        folders.forEach(store::deleteFolder);
    }


    @Test
    @DisplayName("서명 확인 값이 fos-ctx 테스트와 같다")
    void signatureMatchesPluginVector() {
        String key = "2ce07fe9da9032a6ba2d14ea44adf290f530110a4b5d2ed67bb72384342abf8b";
        String signed = String.join(
            "\n",
            "v1-generated-image",
            "fos-root-1",
            "fos-session-1",
            "call_1",
            sha256("image-bytes".getBytes(StandardCharsets.UTF_8)),
            sha256("수채화 고양이".getBytes(StandardCharsets.UTF_8)),
            sha256("창가에 앉은 고양이".getBytes(StandardCharsets.UTF_8)));
        assertThat(hmac(key, signed)).isEqualTo(VECTOR_SIG);
    }


    @Test
    @DisplayName("도는 실행이 올린 그림은 그 대화의 폴더에 원본 그대로 저장되고 주인만 받는다")
    void storesImageInOwnersConversation() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation conversation = conversation();
        running(root, conversation.id(), ExecutionStatus.RUNNING);

        HttpResponse<String> response = upload(root, root, "call_1", "수채화 고양이", "창가에 앉은 고양이를 수채화로", PNG);

        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("title").asString()).isEqualTo("수채화 고양이");
        ChatArtifact row = rows.findAll().stream().filter(it -> it.conversationId().equals(conversation.id())).findFirst().orElseThrow();
        assertThat(row.path()).isEqualTo("generated/수채화 고양이.png");
        assertThat(row.messageId()).as("답에는 turn 이 끝날 때 묶는다").isNull();
        assertThat(row.isGeneratedImage()).isTrue();
        assertThat(row.generationPrompt()).isNotBlank();

        HttpResponse<byte[]> file = file(conversation, row.path(), owner);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).as("내려받은 바이트가 올린 원본과 같다").isEqualTo(PNG);

        AppUser other = users.save(AppUser.of("image-other-" + UUID.randomUUID() + "@example.com", "남", 1L, UserRole.MEMBER));
        assertThat(file(conversation, row.path(), other).statusCode()).as("다른 사용자는 없는 대화와 같다").isEqualTo(404);
        HttpResponse<String> messages = get("/api/v1/chat/conversations/" + conversation.publicId() + "/messages", other);
        assertThat(messages.statusCode()).as("다른 사용자는 메시지 응답으로 지시문을 읽지 못한다").isEqualTo(404);
    }


    @Test
    @DisplayName("같은 제목은 번호를 붙이고 쓸 수 없는 글자는 빼며 제목이 없으면 날짜와 시각이다")
    void numbersDuplicateTitlesAndCleansThem() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation conversation = conversation();
        running(root, conversation.id(), ExecutionStatus.RUNNING);

        assertThat(title(upload(root, root, "call_1", "수채화 고양이", "지시문", PNG))).isEqualTo("수채화 고양이");
        assertThat(title(upload(root, root, "call_2", "수채화 고양이", "지시문", png(65)))).isEqualTo("수채화 고양이 2");
        assertThat(title(upload(root, root, "call_3", " 100% 리얼; ../고양이/ ", "지시문", png(66)))).isEqualTo("100 리얼 고양이");
        assertThat(title(upload(root, root, "call_4", "", "지시문", png(67)))).matches("그림 \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}");
    }


    @Test
    @DisplayName("같은 호출의 같은 그림이 다시 오면 한 줄이고 그림이 다르면 다른 그림이다")
    void repeatedUploadIsOneRow() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation conversation = conversation();
        running(root, conversation.id(), ExecutionStatus.RUNNING);

        assertThat(title(upload(root, root, "call_1", "고양이", "지시문", PNG))).isEqualTo("고양이");
        assertThat(title(upload(root, root, "call_1", "고양이", "지시문", PNG))).isEqualTo("고양이");
        assertThat(title(upload(root, root, "call_1", "고양이", "지시문", png(70)))).isEqualTo("고양이 2");

        assertThat(rows.findGeneratedImagePaths(conversation.id())).hasSize(2);
    }


    @Test
    @DisplayName("부모 turn 이 끝난 뒤 등록된 자식 session 이 올린 그림은 받고 중지한 실행의 그림은 거절한다")
    void acceptsFinishedOriginButRejectsCancelled() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation conversation = conversation();
        AgentExecution turn = running(root, conversation.id(), ExecutionStatus.RUNNING);
        String child = "child-" + UUID.randomUUID();
        bindings.saveAndFlush(HermesSessionBinding.of(PROFILE, child, turn, root, root));
        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", turn.id());

        assertThat(upload(root, child, "call_1", "맡긴 그림", "지시문", PNG).statusCode()).isEqualTo(200);

        jdbc.update("UPDATE agent_execution SET status = 'CANCELLED' WHERE id = ?", turn.id());
        HttpResponse<String> cancelled = upload(root, child, "call_2", "늦은 그림", "지시문", png(71));
        assertThat(cancelled.statusCode()).isEqualTo(403);
        assertThat(rows.findGeneratedImagePaths(conversation.id())).containsExactly("generated/맡긴 그림.png");
    }


    @Test
    @DisplayName("받지 않는 형식과 서명이 틀린 요청, 모르는 session 은 거절하고 어느 것도 404 가 아니다")
    void rejectsWithoutNotFound() throws Exception {
        String root = "fos-" + UUID.randomUUID();
        Conversation conversation = conversation();
        running(root, conversation.id(), ExecutionStatus.RUNNING);

        HttpResponse<String> notImage = upload(root, root, "call_1", "글", "지시문", "<html></html>".getBytes(StandardCharsets.UTF_8));
        assertThat(notImage.statusCode()).isEqualTo(400);

        ObjectNode meta = meta(root, root, "call_2", "고양이", "지시문", PNG);
        meta.put("sig", "0".repeat(64));
        assertThat(send(token, meta.toString(), PNG).statusCode()).isEqualTo(403);

        String unknown = "fos-" + UUID.randomUUID();
        assertThat(upload(unknown, unknown, "call_3", "고양이", "지시문", PNG).statusCode()).isIn(403, 500).isNotEqualTo(404);
        assertThat(send(null, meta(root, root, "call_4", "고양이", "지시문", PNG).toString(), PNG).statusCode())
            .isIn(401, 403)
            .isNotEqualTo(404);
        assertThat(rows.findGeneratedImagePaths(conversation.id())).isEmpty();
    }


    private Conversation conversation() {
        Conversation conversation = conversations.save(Conversation.startedBy(owner.id(), "그림", agent.id(), NOW));
        folders.add(conversation.id());
        return conversation;
    }


    private AgentExecution running(String root, Long conversationId, ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
            .userId(owner.id())
            .agentId(agent.id())
            .conversationId(conversationId)
            .profileName(PROFILE)
            .hermesSessionId(root)
            .costMode(CostMode.SUBSCRIPTION)
            .status(status)
            .startedAt(NOW)
            .build());
    }


    private String title(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("title").asString();
    }


    private HttpResponse<String> upload(String root, String session, String call, String title, String prompt, byte[] image)
        throws Exception {
        return send(token, meta(root, session, call, title, prompt, image).toString(), image);
    }


    private ObjectNode meta(String root, String session, String call, String title, String prompt, byte[] image) {
        ObjectNode meta = json.createObjectNode();
        meta.put("v", 1);
        meta.put("root_session_id", root);
        meta.put("session_id", session);
        meta.put("tool_call_id", call);
        meta.put("title", title);
        meta.put("prompt", prompt);
        meta.put("sig", hmac(sha256(token.getBytes(StandardCharsets.UTF_8)), String.join(
            "\n",
            "v1-generated-image",
            root,
            session,
            call,
            sha256(image),
            sha256(title.getBytes(StandardCharsets.UTF_8)),
            sha256(prompt.getBytes(StandardCharsets.UTF_8)))));
        return meta;
    }


    /** fos-ctx 가 보내는 것과 같은 모양의 multipart 다. 조각은 `meta`(JSON)와 `image`(바이트) 둘이다. */
    private HttpResponse<String> send(String bearer, String meta, byte[] image) throws Exception {
        String boundary = "fos" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"meta\"\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(meta.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"image\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(image);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }


    private HttpResponse<byte[]> file(Conversation conversation, String relativePath, AppUser sender) throws Exception {
        String encoded = String.join("/", Arrays.stream(relativePath.split("/"))
            .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
            .toList());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/v1/chat/conversations/" + conversation.publicId() + "/files/" + encoded))
            .header("Authorization", "Bearer " + jwt(sender))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }


    private HttpResponse<String> get(String path, AppUser sender) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + jwt(sender))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }


    private static String jwt(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.email())
            .claim("name", user.displayName())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }


    /** PNG 머리 바이트 뒤에 `filler` 값을 채운 테스트용 그림이다. 값이 다르면 다른 그림이다. */
    private static byte[] png(int filler) {
        byte[] bytes = new byte[256];
        Arrays.fill(bytes, (byte)filler);
        byte[] magic = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }


    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    private static String hmac(String key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
