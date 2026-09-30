package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 HTTP 경계에서 결과물 쓰기 도구의 인자와 소유권을 확인한다.
 *
 * <p>토큰은 이 검사의 profile 에 묶이고, 도구 호출은 그 profile 로 도는 아빠의 실행 뿌리로 서명한다(ADR-032).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpArtifactWriteToolTest {
    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRows;
    @Autowired AppUserRepository users;
    @Autowired ConversationRepository conversations;
    @Autowired ArtifactStore store;
    @Autowired ChatArtifactRepository artifacts;
    @Autowired AgentExecutionRepository executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubagentSessionRegistrar registrar;

    private static final String PROFILE = "mcp-artifact-write";

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private String dadToken;
    private String dadRoot;
    private AgentExecution dadRun;

    @BeforeEach
    void 준비한다() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        tokenRows.deleteAll();
        conversations.deleteAll();
        users.deleteAll();
        dad = users.save(AppUser.of("mcp-artifact-dad@example.com", "아빠", 1L, UserRole.ADMIN));
        dadToken = tokens.issue(PROFILE, "dad").rawToken();
        dadRoot = McpCallSigner.newRoot();
        dadRun = McpCallSigner.running(executions, dad.id(), 1L, PROFILE, dadRoot);
    }

    @Test
    void 본인_대화에_HTML을_쓰고_공개_결과만_돌려준다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));

        JsonNode result = body(call(dadToken, conversation.publicId().toString(), "test/index.html", "<h1>안녕</h1>"));

        assertThat(result.path("result").path("isError").asBoolean()).isFalse();
        JsonNode output = json.readTree(result.path("result").path("content").get(0).path("text").asString());
        assertThat(output.path("path").asString()).isEqualTo("test/index.html");
        assertThat(output.path("byteSize").asLong()).isEqualTo("<h1>안녕</h1>".getBytes(StandardCharsets.UTF_8).length);
        assertThat(store.resolveInside(conversation.id(), "test/index.html")).isPresent();
        assertThat(artifacts.count()).isZero();
    }

    @Test
    void 서명이_맞는_fos_ctx_를_떼고_지금과_같이_쓰고_검사한다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));
        String fosCtx = McpCallSigner.context(dadToken, "artifact_write", dadRoot).toString();
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversation.publicId() + "\",\"_fos_ctx\":" + fosCtx;

        JsonNode written = body(raw(dadToken, prefix + ",\"path\":\"ctx/index.html\",\"content\":\"<p>x</p>\"}}}"));
        JsonNode unknownKey = body(raw(dadToken, prefix + ",\"path\":\"ctx/other.html\",\"content\":\"x\",\"base64\":\"x\"}}}"));

        assertThat(written.path("result").path("isError").asBoolean()).isFalse();
        assertThat(json.readTree(written.path("result").path("content").get(0).path("text").asString()).path("path").asString())
                .isEqualTo("ctx/index.html");
        assertThat(store.resolveInside(conversation.id(), "ctx/index.html")).isPresent();
        assertThat(unknownKey.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(store.resolveInside(conversation.id(), "ctx/other.html")).isEmpty();
    }

    @Test
    void 틀린_fos_ctx_는_쓰지_않고_거절한다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));
        String zeroSig = "{\"v\":1,\"session_id\":\"" + dadRoot + "\",\"root_session_id\":\"" + dadRoot + "\",\"tool_call_id\":\"c\",\"sig\":\"" + "0".repeat(64) + "\"}";
        // 결과물 폴더는 디스크에 남아 다른 검사의 대화 번호와 겹칠 수 있으므로 경로를 새로 만든다.
        String path = "rejected-" + UUID.randomUUID() + ".html";

        JsonNode rejected = body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversation.publicId() + "\",\"_fos_ctx\":" + zeroSig + ",\"path\":\"" + path + "\",\"content\":\"<p>x</p>\"}}}"));

        assertThat(rejected.path("result").path("isError").asBoolean()).isTrue();
        assertThat(rejected.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.");
        assertThat(store.resolveInside(conversation.id(), path)).isEmpty();
    }

    @Test
    void 등록한_하위_에이전트는_부모_실행이_끝난_뒤에도_그_사용자의_대화에만_쓴다() throws Exception {
        Conversation own = conversations.save(Conversation.startedBy(dad.id(), "", null));
        AppUser kid = users.save(AppUser.of("mcp-artifact-subagent-kid@example.com", "아이", 1L, UserRole.MEMBER));
        Conversation kids = conversations.save(Conversation.startedBy(kid.id(), "", null));
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(PROFILE, dadRoot, dadRoot, subagent);
        jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.SUCCEEDED.name(), dadRun.id());
        // 결과물 폴더는 디스크에 남아 다른 검사의 대화 번호와 겹칠 수 있으므로 경로를 새로 만든다.
        String ownPath = "subagent-" + UUID.randomUUID() + ".html";
        String kidsPath = "subagent-" + UUID.randomUUID() + ".html";

        JsonNode written = body(subagentWrite(subagent, own, ownPath));
        JsonNode refused = body(subagentWrite(subagent, kids, kidsPath));

        assertThat(written.path("result").path("isError").asBoolean()).as("아빠의 대화에 쓴 결과: %s", written).isFalse();
        assertThat(store.resolveInside(own.id(), ownPath)).isPresent();
        assertThat(refused.path("result").path("isError").asBoolean()).as("아이의 대화에 쓴 결과: %s", refused).isTrue();
        assertThat(refused.path("result").path("content").get(0).path("text").asString()).isEqualTo("결과물을 저장할 수 없습니다.");
        assertThat(store.resolveInside(kids.id(), kidsPath)).isEmpty();
    }

    @Test
    void 등록한_하위_에이전트는_부모_실행이_취소되면_쓰지_못한다() throws Exception {
        Conversation own = conversations.save(Conversation.startedBy(dad.id(), "", null));
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(PROFILE, dadRoot, dadRoot, subagent);
        String beforePath = "subagent-" + UUID.randomUUID() + ".html";
        JsonNode beforeCancel = body(subagentWrite(subagent, own, beforePath));
        jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.CANCELLED.name(), dadRun.id());
        String path = "subagent-" + UUID.randomUUID() + ".html";

        JsonNode refused = body(subagentWrite(subagent, own, path));

        assertThat(beforeCancel.path("result").path("isError").asBoolean()).as("부모가 도는 동안 쓴 결과: %s", beforeCancel).isFalse();
        assertThat(store.resolveInside(own.id(), beforePath)).isPresent();
        assertThat(refused.path("result").path("isError").asBoolean()).as("부모를 중지한 뒤 쓴 결과: %s", refused).isTrue();
        assertThat(refused.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.");
        assertThat(store.resolveInside(own.id(), path)).isEmpty();
    }

    @Test
    void 빈_본문과_제어문자가_있는_경로도_유효한_JSON_결과로_돌려준다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));

        JsonNode empty = body(call(dadToken, conversation.publicId().toString(), "empty.html", ""));
        JsonNode escaped = body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversation.publicId() + "\",\"path\":\"test/\\tname.html\",\"content\":\"x\"}}}"));

        assertThat(empty.path("result").path("isError").asBoolean()).isFalse();
        assertThat(json.readTree(empty.path("result").path("content").get(0).path("text").asString()).path("byteSize").asLong()).isZero();
        assertThat(json.readTree(escaped.path("result").path("content").get(0).path("text").asString()).path("path").asString())
                .isEqualTo("test/\tname.html");
    }

    @Test
    void 잘못된_인자와_소유하지_않은_대화는_서로_다른_계약으로_거절한다() throws Exception {
        Conversation own = conversations.save(Conversation.startedBy(dad.id(), "", null));
        AppUser kid = users.save(AppUser.of("mcp-artifact-kid@example.com", "아이", 1L, UserRole.MEMBER));
        Conversation other = conversations.save(Conversation.startedBy(kid.id(), "", null));

        assertThat(body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + own.publicId() + "\",\"path\":\"a.html\",\"content\":null}}}"))
                .path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + own.publicId() + "\",\"path\":\"a.html\",\"content\":\"x\",\"source_url\":\"https://example.com/x.png\"}}}"))
                .path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + own.publicId() + "\",\"path\":\"a.html\",\"content\":\"x\",\"base64\":\"x\"}}}"))
                .path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + other.publicId() + "\",\"path\":\"a.html\",\"content\":\"x\"}}}")).path("result").path("isError").asBoolean()).isTrue();
        assertThat(store.resolveInside(other.id(), "a.html")).isEmpty();
    }

    @Test
    void 본문과_URL_방식의_누락_null_숫자_조합은_인자_오류다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversation.publicId() + "\",\"path\":\"a.html\"";

        for (String suffix : java.util.List.of("}}}", ",\"content\":null}}}", ",\"content\":1}}}",
                ",\"source_url\":null}}}", ",\"source_url\":\"\"}}}", ",\"source_url\":\"  \"}}}")) {
            assertThat(body(raw(dadToken, prefix + suffix)).path("error").path("code").asInt()).isEqualTo(-32602);
        }
    }

    @Test
    void 인자_오류는_고정한_이유를_돌리고_경로와_URL_query를_숨긴다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));
        String conversationId = conversation.publicId().toString();

        JsonNode extension = body(call(dadToken, conversationId, "draft.svg", "x"));
        JsonNode path = body(call(dadToken, conversationId, "../private.html", "x"));
        JsonNode large = body(call(dadToken, conversationId, "large.html", "a".repeat(5 * 1024 * 1024 + 1)));
        HttpResponse<String> invalidUrl = raw(dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversationId + "\",\"path\":\"image.png\",\"source_url\":\"https://images.example.com/image.png?private=value%\"}}}");
        JsonNode url = body(invalidUrl);

        assertThat(extension.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extension.path("error").path("data").asString()).isEqualTo("허용하지 않은 확장자입니다.");
        assertThat(path.path("error").path("data").asString()).isEqualTo("경로 형식이 올바르지 않습니다.");
        assertThat(large.path("error").path("data").asString()).isEqualTo("파일 크기가 5MB를 초과했습니다.");
        assertThat(url.path("error").path("data").asString()).isEqualTo("주소 형식이 올바르지 않습니다.");
        assertThat(path.toString()).doesNotContain("private.html");
        assertThat(url.toString()).doesNotContain("private=value", "image.png");
    }

    @Test
    void 축약_UUID와_모르는_도구는_JSON_RPC_오류다() throws Exception {
        assertThat(body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"1-1-1-1-1\",\"path\":\"a.html\",\"content\":\"x\"}}}")).path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"unknown\",\"arguments\":{}}}")).path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"1\",\"path\":\"a.html\",\"content\":\"x\"}}}")).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void 없는_지운_남의_대화는_같은_오류이고_같은_사용자의_다른_대화는_쓴다() throws Exception {
        Conversation active = conversations.save(Conversation.startedBy(dad.id(), "", null));
        Conversation deleted = conversations.save(Conversation.startedBy(dad.id(), "", null));
        conversations.deleteIfActive(deleted.id(), dad.id(), Instant.now());
        AppUser kid = users.save(AppUser.of("mcp-artifact-owner@example.com", "아이", 1L, UserRole.MEMBER));
        Conversation other = conversations.save(Conversation.startedBy(kid.id(), "", null));

        JsonNode missing = body(call(dadToken, UUID.randomUUID().toString(), "a.html", "x")).path("result");
        JsonNode gone = body(call(dadToken, deleted.publicId().toString(), "a.html", "x")).path("result");
        JsonNode forbidden = body(call(dadToken, other.publicId().toString(), "a.html", "x")).path("result");
        JsonNode own = body(call(dadToken, active.publicId().toString(), "a.html", "x"));

        assertThat(missing).isEqualTo(gone).isEqualTo(forbidden);
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(own.path("result").path("isError").asBoolean()).isFalse();
        assertThat(store.resolveInside(active.id(), "a.html")).isPresent();
    }

    @Test
    void 다시_쓴_HTML은_새_본문으로_바뀌고_URL_실패는_기존_이미지를_보존한다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));
        body(call(dadToken, conversation.publicId().toString(), "page.html", "첫 본문"));
        body(call(dadToken, conversation.publicId().toString(), "page.html", "새 본문"));
        store.write(conversation.id(), "image.png", "before".getBytes(StandardCharsets.UTF_8));

        JsonNode failed = body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversation.publicId() + "\",\"path\":\"image.png\",\"source_url\":\"https://not-allowed.example/image.png?private=value\"}}}"));

        assertThat(Files.readString(store.resolveInside(conversation.id(), "page.html").orElseThrow())).isEqualTo("새 본문");
        assertThat(Files.readString(store.resolveInside(conversation.id(), "image.png").orElseThrow())).isEqualTo("before");
        assertThat(failed.path("result").path("isError").asBoolean()).isTrue();
    }

    @Test
    void 대상이_심볼릭_링크면_저장_실패로_돌리고_기존_파일을_보존한다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));
        store.write(conversation.id(), "original.html", "보존".getBytes(StandardCharsets.UTF_8));
        String path = "linked-" + UUID.randomUUID() + ".html";
        var target = store.resolveForWrite(conversation.id(), path);
        Files.createSymbolicLink(target, target.getParent().resolve("original.html"));
        try {
            JsonNode response = body(call(dadToken, conversation.publicId().toString(), path, "덮어쓰기"));

            assertThat(response.path("error").isMissingNode()).isTrue();
            assertThat(response.path("result").path("isError").asBoolean()).isTrue();
            assertThat(Files.readString(target.getParent().resolve("original.html"))).isEqualTo("보존");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void 도구_설명은_5MB_상한과_엄격한_schema를_공개한다() throws Exception {
        JsonNode tools = body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        JsonNode artifact = tools.path("result").path("tools").get(1);

        assertThat(artifact.path("description").asString()).contains("5MB를 넘을 수 없다");
        assertThat(artifact.path("description").asString()).contains("html", "css", "png", "jpg", "jpeg", "gif", "webp");
        assertThat(artifact.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(artifact.path("inputSchema").path("oneOf")).hasSize(2);
    }

    @Test
    void URL_실패는_주소_경로나_query를_돌려주지_않는다() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null));
        String sourceUrl = "https://not-allowed.example/image.png?private=value";

        JsonNode result = body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversation.publicId() + "\",\"path\":\"image.png\",\"source_url\":\"" + sourceUrl + "\"}}}"));

        assertThat(result.path("result").path("isError").asBoolean()).isTrue();
        assertThat(result.path("result").path("content").get(0).path("text").asString())
                .doesNotContain("not-allowed", "private=value", "image.png");
    }

    private HttpResponse<String> call(String token, String conversationId, String path, String content) throws Exception {
        return raw(token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversationId + "\",\"path\":\"" + path + "\",\"content\":\"" + content + "\"}}}");
    }

    /** 아빠의 뿌리 아래 하위 에이전트 session 에서 부른 것처럼 서명한 {@code artifact_write} 를 보낸다. */
    private HttpResponse<String> subagentWrite(String sessionId, Conversation conversation, String path) throws Exception {
        String fosCtx = McpCallSigner.context(dadToken, "artifact_write", dadRoot, sessionId, "call_" + UUID.randomUUID()).toString();
        return raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"" + conversation.publicId() + "\",\"_fos_ctx\":" + fosCtx + ",\"path\":\"" + path + "\",\"content\":\"<p>x</p>\"}}}");
    }

    /** 도구 호출의 인자에 {@code _fos_ctx} 가 없으면 아빠의 도는 실행 뿌리로 서명해 붙인다. */
    private HttpResponse<String> raw(String token, String request) throws Exception {
        String signed = McpCallSigner.withContext(request, token, dadRoot);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(signed)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
