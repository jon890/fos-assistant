package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 MCP HTTP 경계에서 만든 그림을 원고에 넣는 도구의 결과와 거절 문구를 확인한다(스펙 generated-image REQ18, REQ19).
 */
@BackendIntegrationTest
class ContentDraftImageToolTest {
    private static final String PROFILE = "mcp-content-draft-image-test";
    private static final String TOOL = "content_draft_image";

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AgentRepository agents;

    @Autowired
    com.bifos.assistant.usage.execution.infra.AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    GeneratedImageService images;

    @Autowired
    ArtifactStore store;

    private final HttpClient client = HttpClient.newHttpClient();
    private Conversation conversation;
    private String root;
    private String token;


    @BeforeEach
    void prepare() {
        AppUser owner = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "그림 주인", 1L, UserRole.MEMBER));
        people.save(AllowedPerson.of(owner.email(), "그림 주인", PROFILE));
        var agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(owner.id(), "그림 글", agent.id(), Instant.now()));
        token = tokens.issue(PROFILE, "그림 원고 검사").rawToken();
        root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, agents, owner.id(), conversation.id(), PROFILE, root);
        images.save(conversation.id(), owner.id(), "call-" + UUID.randomUUID(), "글 머리 그림", "지시문", png());
    }


    @AfterEach
    void cleanup() {
        store.deleteFolder(conversation.id());
        jdbc.update("delete from allowed_person where hermes_profile=?", PROFILE);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
    }


    @Test
    @DisplayName("그림 제목을 주면 원고에 쓸 첨부 번호와 파일 이름, 사진 폴더를 준다")
    void returnsAssetForDraft() throws Exception {
        JsonNode response = call(json.createObjectNode().put("title", "글 머리 그림"));

        assertThat(response.path("result").path("isError").booleanValue()).as(response.toString()).isFalse();
        String text = response.path("result").path("content").get(0).path("text").stringValue();
        String begin = "\n<external-data>\n";
        JsonNode data = json.readTree(text.substring(text.indexOf(begin) + begin.length(), text.lastIndexOf("</external-data>")));
        assertThat(data.path("assetId").asString()).matches("[1-9][0-9]*");
        assertThat(data.path("fileName").asString()).isEqualTo(data.path("assetId").asString() + ".png");
        assertThat(data.path("photoDir").asString()).endsWith("/" + conversation.id());
        assertThat(data.path("title").asString()).isEqualTo("글 머리 그림");
    }


    @Test
    @DisplayName("보관 기간이 지난 그림과 없는 제목은 고정 안내와 함께 거절한다")
    void rejectsGoneAndUnknownWithFixedMessage() throws Exception {
        jdbc.update("UPDATE chat_artifact SET deleted_at = CURRENT_TIMESTAMP WHERE conversation_id = ?", conversation.id());

        JsonNode gone = failure(call(json.createObjectNode().put("title", "글 머리 그림")));
        assertThat(gone.path("code").asString()).isEqualTo("IMAGE_SOURCE_GONE");
        assertThat(gone.path("message").asString()).contains("보관 기간이 지나", "원고를 바꾸지 말고");

        JsonNode unknown = failure(call(json.createObjectNode().put("title", "없는 그림")));
        assertThat(unknown.path("code").asString()).isEqualTo("IMAGE_SOURCE_UNKNOWN");
        assertThat(unknown.path("message").asString()).contains("그 제목이 없습니다");
    }


    @Test
    @DisplayName("제목이 없거나 모르는 칸이 있으면 인자 오류다")
    void rejectsInvalidArguments() throws Exception {
        assertThat(call(json.createObjectNode()).path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(call(json.createObjectNode().put("title", "글 머리 그림").put("conversationId", 1))
            .path("error").path("code").asInt()).isEqualTo(-32602);
    }


    private JsonNode failure(JsonNode response) {
        assertThat(response.path("result").path("isError").booleanValue()).as(response.toString()).isTrue();
        return json.readTree(response.path("result").path("content").get(0).path("text").stringValue());
    }


    private JsonNode call(ObjectNode args) throws Exception {
        var signed = args.deepCopy();
        signed.set("_fos_ctx", McpCallSigner.context(token, TOOL, root, root, "call-" + UUID.randomUUID()));
        var rpc = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        rpc.putObject("params").put("name", TOOL).set("arguments", signed);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsString(rpc).getBytes(StandardCharsets.UTF_8))).build(),
            HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body());
    }


    private static byte[] png() {
        byte[] bytes = new byte[128];
        Arrays.fill(bytes, (byte)71);
        byte[] magic = {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }
}
