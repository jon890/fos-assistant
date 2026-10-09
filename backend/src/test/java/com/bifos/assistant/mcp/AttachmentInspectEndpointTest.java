package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
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
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 HTTP에서 원본 bytes와 소비 없는 재검증, SQL 상태 변경 경계를 확인한다. */
@BackendIntegrationTest
class AttachmentInspectEndpointTest {
    private static final String PATH = "/internal/hermes/attachment-inspect";
    private static final String PROFILE = "image-formats-endpoint";
    private static final byte[] GIF = "GIF89a-synthetic-raw".getBytes(StandardCharsets.US_ASCII);

    @LocalServerPort
    int port;

    @Autowired
    AttachmentService attachments;

    @Autowired
    AttachmentProperties properties;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private CurrentUser user;
    private Conversation conversation;
    private AgentExecution execution;
    private ChatAttachment photo;
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
        AppUser owner =
                users.save(AppUser.of(UUID.randomUUID() + "@example.test", "사용자", 1L, UserRole.MEMBER, Instant.now()));
        user = new CurrentUser(owner.id(), owner.email(), owner.displayName(), 1L, UserRole.MEMBER);
        conversation = conversations.save(Conversation.startedBy(user.id(), "사진", null, Instant.now()));
        token = tokens.issue(PROFILE, "검사").rawToken();
        root = McpCallSigner.newRoot();
        execution = executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .profileName(PROFILE)
                .hermesSessionId(root)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now().minusSeconds(1))
                .build());
        photo = attachments.upload(
                user, conversation.id(), "test.gif", "image/gif", GIF.length, new ByteArrayResource(GIF));
        attachments.attach(1L, conversation.id(), List.of(photo.id()));
    }

    @Test
    @DisplayName("원본 bytes를 보존하고 반복 validate는 204이며 조회 예산을 소비하지 않는다")
    void returnsRawAndRepeatedValidationDoesNotConsumeBudget() throws Exception {
        ObjectNode body = request(photo.id());
        HttpResponse<byte[]> raw = send("", body);
        assertThat(raw.statusCode()).isEqualTo(200);
        assertThat(raw.headers().firstValue("content-type")).contains("image/gif");
        assertThat(raw.headers().firstValue("cache-control")).contains("no-store");
        assertThat(raw.body()).containsExactly(GIF);
        for (int attempt = 0; attempt < 61; attempt++) {
            HttpResponse<byte[]> validated = send("/validate", body);
            assertThat(validated.statusCode()).isEqualTo(204);
            assertThat(validated.body()).isEmpty();
        }
        assertThat(send("", body).statusCode()).isEqualTo(200);
        assertThat(send("", body).statusCode()).isEqualTo(403);
        assertThat(send("/validate", body).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("원본 응답 뒤 SQL 삭제 만료 미전송 업로더 변경과 실행 취소를 거절한다")
    void rejectsStateChangesAfterRawResponse() throws Exception {
        ObjectNode body = request(photo.id());
        assertThat(send("", body).statusCode()).isEqualTo(200);
        jdbc.update(
                "update chat_attachment set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                photo.id());
        assertThat(send("/validate", body).statusCode()).isEqualTo(410);
        jdbc.update(
                "update chat_attachment set expires_at = ?, deleted_at = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(60)),
                Timestamp.from(Instant.now()),
                photo.id());
        assertThat(send("/validate", body).statusCode()).isEqualTo(410);
        jdbc.update("update chat_attachment set deleted_at = null, message_id = null where id = ?", photo.id());
        assertThat(send("/validate", body).statusCode()).isEqualTo(400);
        jdbc.update(
                "update chat_attachment set message_id = 1, uploaded_by_user_id = ? where id = ?",
                user.id() + 1000,
                photo.id());
        assertThat(send("/validate", body).statusCode()).isEqualTo(400);
        jdbc.update("update chat_attachment set uploaded_by_user_id = ? where id = ?", user.id(), photo.id());
        jdbc.update("update agent_execution set status = 'CANCELLED' where id = ?", execution.id());
        assertThat(send("/validate", body).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("서명과 region 변조 다른 대화와 원본 크기 불일치는 거절한다")
    void rejectsTamperingOtherConversationAndSizeMismatch() throws Exception {
        ObjectNode body = request(photo.id());
        body.putArray("region").add(0).add(0).add(1).add(1);
        assertThat(send("/validate", body).statusCode()).isEqualTo(403);
        body = request(photo.id());
        ((ObjectNode) body.get("_fos_inspect")).put("sig", "0".repeat(64));
        assertThat(send("/validate", body).statusCode()).isEqualTo(403);
        body = request(photo.id());
        jdbc.update(
                "update chat_attachment set conversation_id = ? where id = ?", conversation.id() + 1000, photo.id());
        assertThat(send("/validate", body).statusCode()).isEqualTo(404);
        jdbc.update(
                "update chat_attachment set conversation_id = ?, byte_size = byte_size + 1 where id = ?",
                conversation.id(),
                photo.id());
        assertThat(send("", body).statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("개요 모드는 서명에 묶고 region과 동시에 받지 않으며 JPEG 원본도 그대로 전달한다")
    void bindsOverviewModeAndReturnsRawJpegOnlyOnExplicitRequest() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        ChatAttachment other = attachments.upload(
                user, conversation.id(), "test.jpg", "image/jpeg", jpeg.length, new ByteArrayResource(jpeg));
        attachments.attach(1L, conversation.id(), List.of(other.id()));
        ObjectNode overview = request(other.id(), true);
        assertThat(send("", overview).body()).containsExactly(jpeg);
        assertThat(send("/validate", overview).statusCode()).isEqualTo(204);
        overview.put("overview", false);
        assertThat(send("/validate", overview).statusCode()).isEqualTo(403);
        ObjectNode together = request(photo.id(), true);
        together.putArray("region").add(0).add(0).add(1).add(1);
        assertThat(send("/validate", together).statusCode()).isEqualTo(400);
    }

    private ObjectNode request(long attachmentId) throws Exception {
        return request(attachmentId, false);
    }

    private ObjectNode request(long attachmentId, boolean overview) throws Exception {
        ObjectNode body =
                json.createObjectNode().put("attachment_id", attachmentId).put("overview", overview);
        ObjectNode context = McpCallSigner.context(token, "attachment_inspect", root);
        body.set("_fos_ctx", context);
        long issued = Instant.now().toEpochMilli();
        String digest = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((attachmentId + "\n" + (overview ? "\noverview=1" : ""))
                                .getBytes(StandardCharsets.UTF_8)));
        String text = String.join(
                "\n",
                "v1-attachment-inspect",
                root,
                root,
                context.get("tool_call_id").asString(),
                Long.toString(issued),
                digest,
                "1");
        String key = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        body.putObject("_fos_inspect")
                .put("issued_at_ms", issued)
                .put("top_level", true)
                .put("sig", HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))));
        return body;
    }

    private HttpResponse<byte[]> send(String suffix, ObjectNode body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH + suffix))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
