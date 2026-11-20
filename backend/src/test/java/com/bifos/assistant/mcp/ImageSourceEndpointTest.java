package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.attachment.config.AttachmentProperties;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 그림을 고칠 바탕 사진을 받아 가는 경로의 권한과 응답을 확인한다(스펙 generated-image REQ15, REQ16).
 *
 * <p>본문은 운영 코드가 아니라 이 테스트가 {@code hermes/plugins/fos-ctx/README.md} 의 「그림을 고칠 사진을 받는다」 대로 따로 만든다.
 */
@BackendIntegrationTest
class ImageSourceEndpointTest {
    private static final String PATH = "/internal/hermes/image-source";
    private static final String PROFILE = "image-source-endpoint";

    @LocalServerPort
    int port;

    @Autowired
    AttachmentService attachments;

    @Autowired
    AttachmentProperties properties;

    @Autowired
    GeneratedImageService images;

    @Autowired
    ArtifactStore store;

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

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> folders = new ArrayList<>();
    private CurrentUser user;
    private Conversation conversation;
    private AgentExecution execution;
    private String token;
    private String root;


    @BeforeEach
    void setUp() throws IOException {
        Path directory = Path.of(properties.root());
        if (Files.exists(directory)) {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        user = member();
        conversation = conversation(user);
        token = tokens.issue(PROFILE, "검사").rawToken();
        root = McpCallSigner.newRoot();
        execution = run(user, conversation, root);
    }


    @AfterEach
    void tearDown() {
        folders.forEach(store::deleteFolder);
    }


    @Test
    @DisplayName("이 대화에 보낸 사진 세 장과 만든 그림을 원본 바이트 그대로 주고 원본은 바뀌지 않는다")
    void returnsSentPhotosAndGeneratedImageUnchanged() throws Exception {
        byte[] first = gif("one");
        byte[] second = gif("two");
        byte[] third = gif("three");
        List<ChatAttachment> photos = sent(user, conversation, first, second, third);
        byte[] png = png(41);
        images.save(conversation.id(), user.id(), "call-key-" + UUID.randomUUID(), "수채화 고양이", "창가의 고양이", png);

        for (int index = 0; index < photos.size(); index++) {
            HttpResponse<byte[]> photo = send("attachment:" + photos.get(index).id());
            assertThat(photo.statusCode()).isEqualTo(200);
            assertThat(photo.headers().firstValue("content-type")).contains("image/gif");
            assertThat(photo.headers().firstValue("cache-control")).contains("no-store");
            assertThat(photo.body()).containsExactly(List.of(first, second, third).get(index));
        }
        HttpResponse<byte[]> generated = send("generated:수채화 고양이");
        assertThat(generated.statusCode()).isEqualTo(200);
        assertThat(generated.headers().firstValue("content-type")).contains("image/png");
        assertThat(generated.body()).containsExactly(png);

        assertThat(attachments.read(user, conversation.id(), photos.get(0).id()).body().readAllBytes())
            .as("받은 뒤에도 올린 사진의 바이트는 그대로다")
            .containsExactly(first);
    }


    @Test
    @DisplayName("다른 대화와 다른 사용자의 사진, 보내지 않은 사진, 없는 제목과 모양이 틀린 가리킴은 422 이고 404 가 아니다")
    void rejectsWhatIsNotInTheConversation() throws Exception {
        Conversation otherConversation = conversation(user);
        ChatAttachment elsewhere = sent(user, otherConversation, gif("elsewhere")).getFirst();
        CurrentUser stranger = member();
        ChatAttachment strangers = sent(stranger, conversation(stranger), gif("stranger")).getFirst();
        byte[] unsentBytes = gif("unsent");
        ChatAttachment unsent = attachments.upload(
            user, conversation.id(), "unsent.gif", "image/gif", unsentBytes.length, new ByteArrayResource(unsentBytes));
        images.save(otherConversation.id(), user.id(), "call-key-" + UUID.randomUUID(), "다른 대화 그림", "지시문", png(42));

        for (String source : List.of(
            "attachment:" + elsewhere.id(),
            "attachment:" + strangers.id(),
            "attachment:" + unsent.id(),
            "attachment:999999999",
            "generated:다른 대화 그림",
            "generated:없는 제목",
            "/root/.hermes/cache/images/photo.png",
            "https://example.com/photo.png",
            "attachment:abc")) {
            HttpResponse<byte[]> response = send(source);
            assertThat(response.statusCode()).as(source).isEqualTo(422);
            assertThat(code(response)).as(source).isEqualTo("IMAGE_SOURCE_UNKNOWN");
        }
    }


    @Test
    @DisplayName("지운 사진과 보관 기간 정리로 파일이 지워진 그림은 410 이다")
    void deletedSourcesAreGone() throws Exception {
        ChatAttachment photo = sent(user, conversation, gif("deleted")).getFirst();
        attachments.deleteByUser(user, conversation.id(), photo.id());
        images.save(conversation.id(), user.id(), "call-key-" + UUID.randomUUID(), "지난 그림", "지시문", png(43));
        jdbc.update(
            "UPDATE chat_artifact SET deleted_at = CURRENT_TIMESTAMP WHERE conversation_id = ? AND path = ?",
            conversation.id(),
            "generated/지난 그림.png");

        for (String source : List.of("attachment:" + photo.id(), "generated:지난 그림")) {
            HttpResponse<byte[]> response = send(source);
            assertThat(response.statusCode()).as(source).isEqualTo(410);
            assertThat(code(response)).as(source).isEqualTo("IMAGE_SOURCE_GONE");
        }
    }


    @Test
    @DisplayName("끝난 origin 의 자식은 받고 중지한 실행과 서명이 틀린 맥락은 403 이다")
    void followsOriginRules() throws Exception {
        ChatAttachment photo = sent(user, conversation, gif("child")).getFirst();
        String child = "child-" + UUID.randomUUID();
        bindings.saveAndFlush(HermesSessionBinding.of(PROFILE, child, execution, root, root));
        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", execution.id());
        ObjectNode fromChild = body(McpCallSigner.context(token, "image_generate", root, child, "call_child"), "attachment:" + photo.id());
        assertThat(post(fromChild).statusCode()).as("맡긴 실행은 부모 turn 뒤에 돈다").isEqualTo(200);

        ObjectNode wrongTool = body(McpCallSigner.context(token, "attachment_inspect", root), "attachment:" + photo.id());
        assertThat(post(wrongTool).statusCode()).isEqualTo(403);

        jdbc.update("UPDATE agent_execution SET status = 'CANCELLED' WHERE id = ?", execution.id());
        assertThat(post(fromChild).statusCode()).isEqualTo(403);
    }


    private List<ChatAttachment> sent(CurrentUser owner, Conversation into, byte[]... bodies) {
        List<ChatAttachment> uploaded = new ArrayList<>();
        for (byte[] body : bodies) {
            uploaded.add(attachments.upload(
                owner, into.id(), "photo.gif", "image/gif", body.length, new ByteArrayResource(body)));
        }
        attachments.attach(1L, into.id(), uploaded.stream().map(ChatAttachment::id).toList());
        return uploaded;
    }


    private CurrentUser member() {
        AppUser saved = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "사용자", 1L, UserRole.MEMBER));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), 1L, UserRole.MEMBER);
    }


    private Conversation conversation(CurrentUser owner) {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), "사진", null, Instant.now()));
        folders.add(saved.id());
        return saved;
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


    private ObjectNode body(ObjectNode context, String source) {
        ObjectNode body = json.createObjectNode();
        body.set("_fos_ctx", context);
        body.put("source", source);
        return body;
    }


    private HttpResponse<byte[]> send(String source) throws Exception {
        return post(body(McpCallSigner.context(token, "image_generate", root), source));
    }


    private HttpResponse<byte[]> post(ObjectNode body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }


    private String code(HttpResponse<byte[]> response) {
        return json.readTree(new String(response.body(), StandardCharsets.UTF_8)).path("code").asString();
    }


    private static byte[] gif(String marker) {
        return ("GIF89a-" + marker).getBytes(StandardCharsets.US_ASCII);
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
