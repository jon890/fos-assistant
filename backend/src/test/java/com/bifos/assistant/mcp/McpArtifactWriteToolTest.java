package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.infra.ArtifactProperties;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * <p>토큰은 이 검사의 profile 에 묶이고, 도구 호출은 그 profile 로 도는 아빠의 실행 루트로 서명한다(ADR-032).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpArtifactWriteToolTest {
    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentTokenRepository tokenRows;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ArtifactStore store;

    @Autowired
    ArtifactProperties properties;

    @Autowired
    ChatArtifactRepository artifacts;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubagentSessionRegistrar registrar;

    private static final String PROFILE = "mcp-artifact-write";

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private String dadToken;
    private String dadRoot;
    private AgentExecution dadRun;

    @BeforeEach
    void setUp() throws Exception {
        // 이 서버는 따로 뜬 메모리 데이터베이스를 써서 대화 번호가 겹칠 수 있다. 다른 테스트가 남긴 폴더와 링크를 비운다.
        deleteTree(Path.of(properties.root()).toAbsolutePath());
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        tokenRows.deleteAll();
        conversations.deleteAll();
        users.deleteAll();
        dad = users.save(AppUser.of("mcp-artifact-dad@example.com", "아빠", 1L, UserRole.ADMIN, Instant.now()));
        dadToken = tokens.issue(PROFILE, "dad").rawToken();
        dadRoot = McpCallSigner.newRoot();
        dadRun = McpCallSigner.running(executions, dad.id(), 1L, PROFILE, dadRoot);
    }

    @Test
    @DisplayName("본인 대화에 HTML을 쓰고 공개 결과만 돌려준다")
    void writesHtmlToOwnConversationAndReturnsOnlyPublicResult() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));

        JsonNode result = body(call(dadToken, conversation.publicId().toString(), "test/index.html", "<h1>안녕</h1>"));

        assertThat(result.path("result").path("isError").asBoolean()).isFalse();
        JsonNode output = json.readTree(
                result.path("result").path("content").get(0).path("text").asString());
        assertThat(output.path("path").asString()).isEqualTo("test/index.html");
        assertThat(output.path("byteSize").asLong()).isEqualTo("<h1>안녕</h1>".getBytes(StandardCharsets.UTF_8).length);
        assertThat(store.resolveInside(conversation.id(), "test/index.html")).isPresent();
        assertThat(artifacts.count()).isZero();
    }

    @Test
    @DisplayName("서명이 맞는 fos ctx 를 떼고 지금과 같이 쓰고 검사한다")
    void stripsValidlySignedFosCtxAndWritesAndChecksAsBefore() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        String fosCtx =
                McpCallSigner.context(dadToken, "artifact_write", dadRoot).toString();
        String prefix =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversation.publicId() + "\",\"_fos_ctx\":" + fosCtx;

        JsonNode written = body(raw(dadToken, prefix + ",\"path\":\"ctx/index.html\",\"content\":\"<p>x</p>\"}}}"));
        JsonNode unknownKey =
                body(raw(dadToken, prefix + ",\"path\":\"ctx/other.html\",\"content\":\"x\",\"base64\":\"x\"}}}"));

        assertThat(written.path("result").path("isError").asBoolean()).isFalse();
        assertThat(json.readTree(written.path("result")
                                .path("content")
                                .get(0)
                                .path("text")
                                .asString())
                        .path("path")
                        .asString())
                .isEqualTo("ctx/index.html");
        assertThat(store.resolveInside(conversation.id(), "ctx/index.html")).isPresent();
        assertThat(unknownKey.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(store.resolveInside(conversation.id(), "ctx/other.html")).isEmpty();
    }

    @Test
    @DisplayName("틀린 fos ctx 는 쓰지 않고 거절한다")
    void rejectsWrongFosCtxWithoutWriting() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        String zeroSig = "{\"v\":1,\"session_id\":\"" + dadRoot + "\",\"root_session_id\":\"" + dadRoot
                + "\",\"tool_call_id\":\"c\",\"sig\":\"" + "0".repeat(64) + "\"}";
        // 결과물 폴더는 디스크에 남아 다른 검사의 대화 번호와 겹칠 수 있으므로 경로를 새로 만든다.
        String path = "rejected-" + UUID.randomUUID() + ".html";

        JsonNode rejected = body(raw(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversation.publicId() + "\",\"_fos_ctx\":" + zeroSig + ",\"path\":\"" + path
                        + "\",\"content\":\"<p>x</p>\"}}}"));

        assertThat(rejected.path("result").path("isError").asBoolean()).isTrue();
        assertThat(rejected.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.");
        assertThat(store.resolveInside(conversation.id(), path)).isEmpty();
    }

    @Test
    @DisplayName("등록한 하위 에이전트는 부모 실행이 끝난 뒤에도 그 사용자의 대화에만 쓴다")
    void registeredSubagentWritesOnlyToItsUsersConversationAfterParentEnds() throws Exception {
        Conversation own = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        AppUser kid = users.save(
                AppUser.of("mcp-artifact-subagent-kid@example.com", "아이", 1L, UserRole.MEMBER, Instant.now()));
        Conversation kids = conversations.save(Conversation.startedBy(kid.id(), "", null, Instant.now()));
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(PROFILE, dadRoot, dadRoot, subagent);
        jdbc.update(
                "UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.SUCCEEDED.name(), dadRun.id());
        // 결과물 폴더는 디스크에 남아 다른 검사의 대화 번호와 겹칠 수 있으므로 경로를 새로 만든다.
        String ownPath = "subagent-" + UUID.randomUUID() + ".html";
        String kidsPath = "subagent-" + UUID.randomUUID() + ".html";

        JsonNode written = body(subagentWrite(subagent, own, ownPath));
        JsonNode refused = body(subagentWrite(subagent, kids, kidsPath));

        assertThat(written.path("result").path("isError").asBoolean())
                .as("아빠의 대화에 쓴 결과: %s", written)
                .isFalse();
        assertThat(store.resolveInside(own.id(), ownPath)).isPresent();
        assertThat(refused.path("result").path("isError").asBoolean())
                .as("아이의 대화에 쓴 결과: %s", refused)
                .isTrue();
        assertThat(refused.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("결과물을 저장할 수 없습니다.");
        assertThat(store.resolveInside(kids.id(), kidsPath)).isEmpty();
    }

    @Test
    @DisplayName("등록한 하위 에이전트는 부모 실행이 취소되면 쓰지 못한다")
    void registeredSubagentCannotWriteWhenParentRunIsCancelled() throws Exception {
        Conversation own = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(PROFILE, dadRoot, dadRoot, subagent);
        String beforePath = "subagent-" + UUID.randomUUID() + ".html";
        JsonNode beforeCancel = body(subagentWrite(subagent, own, beforePath));
        jdbc.update(
                "UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.CANCELLED.name(), dadRun.id());
        String path = "subagent-" + UUID.randomUUID() + ".html";

        JsonNode refused = body(subagentWrite(subagent, own, path));

        assertThat(beforeCancel.path("result").path("isError").asBoolean())
                .as("부모가 도는 동안 쓴 결과: %s", beforeCancel)
                .isFalse();
        assertThat(store.resolveInside(own.id(), beforePath)).isPresent();
        assertThat(refused.path("result").path("isError").asBoolean())
                .as("부모를 중지한 뒤 쓴 결과: %s", refused)
                .isTrue();
        assertThat(refused.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.");
        assertThat(store.resolveInside(own.id(), path)).isEmpty();
    }

    @Test
    @DisplayName("빈 본문과 제어문자가 있는 경로도 유효한 JSON 결과로 돌려준다")
    void returnsValidJsonResultForEmptyBodyAndPathWithControlChars() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));

        JsonNode empty = body(call(dadToken, conversation.publicId().toString(), "empty.html", ""));
        JsonNode escaped = body(raw(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversation.publicId() + "\",\"path\":\"test/\\tname.html\",\"content\":\"x\"}}}"));

        assertThat(empty.path("result").path("isError").asBoolean()).isFalse();
        assertThat(json.readTree(empty.path("result")
                                .path("content")
                                .get(0)
                                .path("text")
                                .asString())
                        .path("byteSize")
                        .asLong())
                .isZero();
        assertThat(json.readTree(escaped.path("result")
                                .path("content")
                                .get(0)
                                .path("text")
                                .asString())
                        .path("path")
                        .asString())
                .isEqualTo("test/\tname.html");
    }

    @Test
    @DisplayName("잘못된 인자와 소유하지 않은 대화는 서로 다른 계약으로 거절한다")
    void rejectsBadArgumentsAndUnownedConversationWithDifferentContracts() throws Exception {
        Conversation own = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        AppUser kid = users.save(AppUser.of("mcp-artifact-kid@example.com", "아이", 1L, UserRole.MEMBER, Instant.now()));
        Conversation other = conversations.save(Conversation.startedBy(kid.id(), "", null, Instant.now()));

        assertThat(body(raw(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                                        + own.publicId() + "\",\"path\":\"a.html\",\"content\":null}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
        assertThat(body(raw(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                                        + own.publicId()
                                        + "\",\"path\":\"a.html\",\"content\":\"x\",\"source_url\":\"https://example.com/x.png\"}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
        assertThat(body(raw(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                                        + own.publicId()
                                        + "\",\"path\":\"a.html\",\"content\":\"x\",\"base64\":\"x\"}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
        assertThat(body(raw(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                                        + other.publicId() + "\",\"path\":\"a.html\",\"content\":\"x\"}}}"))
                        .path("result")
                        .path("isError")
                        .asBoolean())
                .isTrue();
        assertThat(store.resolveInside(other.id(), "a.html")).isEmpty();
    }

    @Test
    @DisplayName("본문과 URL 방식의 누락 null 숫자 조합은 인자 오류다")
    void bodyAndUrlModeMissingNullAndNumberCombinationsAreArgumentErrors() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        String prefix =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversation.publicId() + "\",\"path\":\"a.html\"";

        for (String suffix : List.of(
                "}}}",
                ",\"content\":null}}}",
                ",\"content\":1}}}",
                ",\"source_url\":null}}}",
                ",\"source_url\":\"\"}}}",
                ",\"source_url\":\"  \"}}}")) {
            assertThat(body(raw(dadToken, prefix + suffix))
                            .path("error")
                            .path("code")
                            .asInt())
                    .isEqualTo(-32602);
        }
    }

    @Test
    @DisplayName("인자 오류는 고정한 이유를 돌리고 경로와 URL query를 숨긴다")
    void argumentErrorReturnsFixedReasonAndHidesPathAndUrlQuery() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        String conversationId = conversation.publicId().toString();

        JsonNode extension = body(call(dadToken, conversationId, "draft.svg", "x"));
        JsonNode path = body(call(dadToken, conversationId, "../private.html", "x"));
        JsonNode large = body(call(dadToken, conversationId, "large.html", "a".repeat(5 * 1024 * 1024 + 1)));
        HttpResponse<String> invalidUrl = raw(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversationId
                        + "\",\"path\":\"image.png\",\"source_url\":\"https://images.example.com/image.png?private=value%\"}}}");
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
    @DisplayName("축약 UUID와 모르는 도구는 JSON RPC 오류다")
    void shortenedUuidAndUnknownToolAreJsonRpcErrors() throws Exception {
        assertThat(body(raw(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"1-1-1-1-1\",\"path\":\"a.html\",\"content\":\"x\"}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
        assertThat(body(raw(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"unknown\",\"arguments\":{}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32601);
        assertThat(body(raw(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\"1\",\"path\":\"a.html\",\"content\":\"x\"}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
    }

    @Test
    @DisplayName("없는 지운 남의 대화는 같은 오류이고 같은 사용자의 다른 대화는 쓴다")
    void missingDeletedAndOthersConversationsGiveSameError() throws Exception {
        Conversation active = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        Conversation deleted = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        conversationWriter.deleteIfActive(deleted.id(), dad.id(), Instant.now());
        AppUser kid =
                users.save(AppUser.of("mcp-artifact-owner@example.com", "아이", 1L, UserRole.MEMBER, Instant.now()));
        Conversation other = conversations.save(Conversation.startedBy(kid.id(), "", null, Instant.now()));

        JsonNode missing = body(call(dadToken, UUID.randomUUID().toString(), "a.html", "x"))
                .path("result");
        JsonNode gone = body(call(dadToken, deleted.publicId().toString(), "a.html", "x"))
                .path("result");
        JsonNode forbidden =
                body(call(dadToken, other.publicId().toString(), "a.html", "x")).path("result");
        JsonNode own = body(call(dadToken, active.publicId().toString(), "a.html", "x"));

        assertThat(missing).isEqualTo(gone).isEqualTo(forbidden);
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(own.path("result").path("isError").asBoolean()).isFalse();
        assertThat(store.resolveInside(active.id(), "a.html")).isPresent();
    }

    @Test
    @DisplayName("다시 쓴 HTML은 새 본문으로 바뀌고 URL 실패는 기존 이미지를 보존한다")
    void rewrittenHtmlChangesToNewBodyAndUrlFailureKeepsExistingImage() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        body(call(dadToken, conversation.publicId().toString(), "page.html", "첫 본문"));
        body(call(dadToken, conversation.publicId().toString(), "page.html", "새 본문"));
        store.write(conversation.id(), "image.png", "before".getBytes(StandardCharsets.UTF_8));

        JsonNode failed = body(
                raw(
                        dadToken,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                                + conversation.publicId()
                                + "\",\"path\":\"image.png\",\"source_url\":\"https://not-allowed.example/image.png?private=value\"}}}"));

        assertThat(Files.readString(
                        store.resolveInside(conversation.id(), "page.html").orElseThrow()))
                .isEqualTo("새 본문");
        assertThat(Files.readString(
                        store.resolveInside(conversation.id(), "image.png").orElseThrow()))
                .isEqualTo("before");
        assertThat(failed.path("result").path("isError").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("대상이 심볼릭 링크면 저장 실패로 돌리고 기존 파일을 보존한다")
    void failsSaveAndKeepsExistingFileWhenTargetIsSymlink() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        store.write(conversation.id(), "original.html", "보존".getBytes(StandardCharsets.UTF_8));
        String path = "linked-" + UUID.randomUUID() + ".html";
        var target = store.resolveForWrite(conversation.id(), path);
        Files.createSymbolicLink(target, target.getParent().resolve("original.html"));
        try {
            JsonNode response = body(call(dadToken, conversation.publicId().toString(), path, "덮어쓰기"));

            assertThat(response.path("error").isMissingNode()).isTrue();
            assertThat(response.path("result").path("isError").asBoolean()).isTrue();
            assertThat(Files.readString(target.getParent().resolve("original.html")))
                    .isEqualTo("보존");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("도구 설명은 5MB 상한과 엄격한 schema를 공개한다")
    void toolDescriptionPublishes5MbLimitAndStrictSchema() throws Exception {
        JsonNode tools = body(raw(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        JsonNode artifact = tools.path("result").path("tools").get(1);

        assertThat(artifact.path("description").asString()).contains("5MB를 넘을 수 없다");
        assertThat(artifact.path("description").asString())
                .contains("html", "css", "png", "jpg", "jpeg", "gif", "webp");
        assertThat(artifact.path("inputSchema").path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(artifact.path("inputSchema").path("oneOf")).hasSize(2);
    }

    @Test
    @DisplayName("URL 실패는 주소 경로나 query를 돌려주지 않는다")
    void urlFailureDoesNotReturnAddressPathOrQuery() throws Exception {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "", null, Instant.now()));
        String sourceUrl = "https://not-allowed.example/image.png?private=value";

        JsonNode result = body(raw(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversation.publicId() + "\",\"path\":\"image.png\",\"source_url\":\"" + sourceUrl
                        + "\"}}}"));

        assertThat(result.path("result").path("isError").asBoolean()).isTrue();
        assertThat(result.path("result").path("content").get(0).path("text").asString())
                .doesNotContain("not-allowed", "private=value", "image.png");
    }

    private HttpResponse<String> call(String token, String conversationId, String path, String content)
            throws Exception {
        return raw(
                token,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversationId + "\",\"path\":\"" + path + "\",\"content\":\"" + content + "\"}}}");
    }

    /** 아빠의 루트 아래 하위 에이전트 session 에서 부른 것처럼 서명한 {@code artifact_write} 를 보낸다. */
    private HttpResponse<String> subagentWrite(String sessionId, Conversation conversation, String path)
            throws Exception {
        String fosCtx = McpCallSigner.context(
                        dadToken, "artifact_write", dadRoot, sessionId, "call_" + UUID.randomUUID())
                .toString();
        return raw(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"artifact_write\",\"arguments\":{\"conversation_id\":\""
                        + conversation.publicId() + "\",\"_fos_ctx\":" + fosCtx + ",\"path\":\"" + path
                        + "\",\"content\":\"<p>x</p>\"}}}");
    }

    /** 도구 호출의 인자에 {@code _fos_ctx} 가 없으면 아빠의 도는 실행 루트로 서명해 붙인다. */
    private HttpResponse<String> raw(String token, String request) throws Exception {
        String signed = McpCallSigner.withContext(request, token, dadRoot);
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(signed))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
