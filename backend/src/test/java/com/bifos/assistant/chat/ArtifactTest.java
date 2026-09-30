package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ArtifactCleaner;
import com.bifos.assistant.chat.application.ArtifactProperties;
import com.bifos.assistant.chat.application.ArtifactService;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.skill.application.SkillAgentNotice;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 대화 폴더에 만든 HTML 을 답에 묶고, 그 폴더의 파일을 스크립트가 돌지 않는 머리글과 함께 주는 것을
 * 확인한다.
 *
 * <p>폴더 밖 판정과 머리글은 실제 서버와 HTTP 클라이언트로 본다. 정규화되지 않은 경로는 컨트롤러 앞의 방화벽이
 * 거절하는데, 그 층은 실제 요청이 지나야 확인된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ArtifactTest.StubRuntime.class)
class ArtifactTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String AGENT_ARTIFACT_ROOT = "/agent-side/artifacts";
    private static final String PREAMBLE_GUIDE =
            "이 폴더는 사용자가 HTML 페이지나 이미지 같은 파일을 만들어 달라고 할 때만 쓴다. 그런 요청이 없으면 파일을 만들지 않고 답을 글로만 한다.\n"
                    + "artifact_write 도구가 있으면 그것으로 저장한다. conversation_id 에 이 대화 식별자를 넣고 path 는 상대 경로로 쓴다.\n"
                    + "artifact_write 도구가 없고 파일 도구가 있으면 위 폴더에 결과물 파일을 직접 쓴다.\n"
                    + "artifact_write 에서는 HTML 과 CSS 는 content, 이미지는 source_url 을 쓴다. 둘 중 하나만 넣는다. 파일 하나는 5MB 까지다.\n"
                    + "HTML 이 사진을 부를 때는 이 폴더 안의 상대 경로를 쓴다.\n"
                    + "이 폴더 경로와 파일 경로를 답에 쓰지 않는다. 만든 결과물은 답 아래에 자동으로 붙는다.";
    private static final String CSP = "sandbox allow-same-origin allow-popups allow-popups-to-escape-sandbox; "
            + "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; base-uri 'none'; "
            + "form-action 'none'";
    private static final String CHIEF_MARK = "조사할 것과 만들 것을 나눈다";
    private static final String HTML = "<!doctype html><title>초안</title><p>초안</p>";

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @LocalServerPort int port;
    @Autowired ChatService chat;
    @Autowired ArtifactService artifactService;
    @Autowired ArtifactStore store;
    @Autowired ArtifactCleaner cleaner;
    @Autowired ArtifactProperties properties;
    @Autowired ChatArtifactRepository artifactRows;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationRepository conversations;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired HermesRunsClient hermes;

    /** 스트림 경로의 사건 중계는 이 검사가 보는 것이 아니다. 열자마자 끝나게 둔다. */
    @MockitoBean HermesRunEventStream eventStream;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private CurrentUser dad;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        stub().reset();
        stub().willReturn(completed("run-1", "만들었어요"));
        artifactRows.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        // 이 서버는 따로 뜬 메모리 데이터베이스를 써서 대화 번호가 겹칠 수 있다. 앞선 실행이 남긴 폴더를 비운다.
        root = Path.of(properties.root()).toAbsolutePath();
        deleteTree(root);
        dad = member("artifact-dad@example.com");
        agentOf(dad, "dad", null);
    }

    @Test
    @DisplayName("스트림 turn 이 도는 사이 쓴 HTML 하나만 답에 묶이고 사진과 CSS 는 묶이지 않는다")
    void bindsOnlyOneHtmlWrittenDuringStreamTurnNotImagesOrCss() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        stub().beforeAwait(() -> {
            writeDuringTurn(conversation.id(), "a/index.html", HTML);
            writeDuringTurn(conversation.id(), "a/photo.png", "png");
            writeDuringTurn(conversation.id(), "a/style.css", "p{}");
        });

        chat.stream(dad, conversation.id(), "초안 만들어 줘", null, event -> {});

        assertThat(stub().received()).singleElement().extracting(HermesRunCommand::input)
                .as("파일 도구를 쓰는 에이전트도 결과물 폴더에 직접 쓸 수 있어야 한다")
                .asString().contains("artifact_write 도구가 없고 파일 도구가 있으면 위 폴더에 결과물 파일을 직접 쓴다.");
        assertThat(answerArtifacts(conversation))
                .containsExactly(List.of("a/index.html", String.valueOf(htmlBytes()), "false"));
    }

    @Test
    @DisplayName("스트림이 아닌 보내기로 돈 turn 이 쓴 파일도 그 답에 묶인다")
    void bindsFilesWrittenByNonStreamSendToItsReply() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        stub().beforeAwait(() -> writeDuringTurn(conversation.id(), "a/index.html", HTML));

        HttpResponse<String> sent = post("/api/v1/chat/messages",
                "{\"conversationId\":\"" + conversation.publicId() + "\",\"text\":\"초안 만들어 줘\"}");

        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);
        assertThat(answerArtifacts(conversation)).extracting(row -> row.getFirst()).containsExactly("a/index.html");
    }

    @Test
    @DisplayName("Hermes 로 간 입력은 결과물 폴더 단락으로 시작하고 사용자가 쓴 글로 끝난다")
    void hermesInputStartsWithOutputFolderParagraphAndEndsWithUserText() {
        Conversation conversation = chat.startEmpty(dad, "dad");

        chat.send(dad, conversation.id(), "안녕", null);

        assertThat(stub().received()).singleElement().extracting(HermesRunCommand::input).isEqualTo(
                "[결과물 폴더]\n"
                        + AGENT_ARTIFACT_ROOT + "/" + conversation.id() + "\n"
                        + "대화 식별자: " + conversation.publicId() + "\n"
                        + PREAMBLE_GUIDE + "\n"
                        + "\n"
                        + SkillAgentNotice.PARAGRAPH
                        + "안녕");
        assertThat(Files.isDirectory(root.resolve(String.valueOf(conversation.id()))))
                .as("turn 을 시작할 때 대화 폴더를 만든다")
                .isTrue();
    }

    @Test
    @DisplayName("Hermes 로 간 입력에 스킬 관리 단락이 들어 있다")
    void hermesInputContainsSkillManagementParagraph() {
        Conversation conversation = chat.startEmpty(dad, "dad");

        chat.send(dad, conversation.id(), "안녕", null);

        assertThat(stub().received()).singleElement().extracting(HermesRunCommand::input).asString()
                .contains("[스킬 관리]\n")
                .contains("skill_manage 로 스킬을 만들거나 고치지 않는다.");
    }

    @Test
    @DisplayName("흐름 turn 의 하위 실행으로 간 입력에도 스킬 관리 단락이 들어 있다")
    void flowChildRunInputContainsSkillManagementParagraph() {
        CurrentUser mom = member("artifact-mom@example.com");
        agentOf(mom, "flow-mom", ResearchAndBuildFlow.NAME);
        stub().willAnswer(command -> command.input().contains(CHIEF_MARK)
                ? completed("run-chief", "{\"research\":\"보조금을 조사해\",\"build\":\"표를 만든다\"}")
                : completed("run-child", "단계 답"));

        chat.send(mom, null, "보조금 비교해 줘", "flow-mom");

        assertThat(stub().received()).extracting(HermesRunCommand::input)
                .isNotEmpty()
                .allSatisfy(input -> assertThat(input)
                        .contains("[스킬 관리]\n")
                        .contains("skill_manage 로 스킬을 만들거나 고치지 않는다."));
    }

    @Test
    @DisplayName("흐름 turn 의 하위 실행으로 간 입력은 모두 결과물 폴더 단락으로 시작한다")
    void flowChildRunInputsAllStartWithOutputFolderParagraph() {
        CurrentUser mom = member("artifact-mom@example.com");
        agentOf(mom, "flow-mom", ResearchAndBuildFlow.NAME);
        stub().willAnswer(command -> command.input().contains(CHIEF_MARK)
                ? completed("run-chief", "{\"research\":\"보조금을 조사해\",\"build\":\"표를 만든다\"}")
                : completed("run-child", "단계 답"));

        chat.send(mom, null, "보조금 비교해 줘", "flow-mom");

        Long conversationId = executions.findAll().getFirst().conversationId();
        Conversation conversation = conversations.findById(conversationId).orElseThrow();
        String preamble = artifactService.agentPreamble(conversation);
        List<String> inputs = stub().received().stream().map(HermesRunCommand::input).toList();
        List<String> children = inputs.stream().filter(input -> !input.contains(CHIEF_MARK)).toList();
        assertThat(children).as("Researcher, Engineer, Synthesizer").hasSize(3)
                .allSatisfy(input -> assertThat(input).startsWith(preamble));
        assertThat(inputs).filteredOn(input -> input.contains(CHIEF_MARK)).singleElement()
                .satisfies(chief -> assertThat(chief).contains(preamble));
    }

    @Test
    @DisplayName("turn 이 시작하기 전에 있던 HTML 은 묶이지 않는다")
    void doesNotBindHtmlThatExistedBeforeTurnStarted() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "old.html", HTML, Instant.now().minus(Duration.ofMinutes(5)));
        stub().beforeAwait(() -> writeDuringTurn(conversation.id(), "new.html", HTML));

        chat.send(dad, conversation.id(), "새로 만들어 줘", null);

        assertThat(answerArtifacts(conversation)).extracting(row -> row.getFirst()).containsExactly("new.html");
    }

    @Test
    @DisplayName("같은 파일을 다음 turn 이 다시 고치면 두 답에 각각 한 행이 생긴다")
    void editingSameFileInNextTurnCreatesOneRowPerReply() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        stub().beforeAwait(() -> writeDuringTurn(conversation.id(), "a/index.html", HTML));

        chat.send(dad, conversation.id(), "만들어 줘", null);
        chat.send(dad, conversation.id(), "고쳐 줘", null);

        List<Long> answers = assistantMessageIds(conversation.id());
        assertThat(answers).hasSize(2);
        assertThat(artifactRows.findByMessageIdInOrderByIdAsc(answers))
                .extracting(ChatArtifact::messageId, ChatArtifact::path)
                .containsExactly(
                        tuple(answers.get(0), "a/index.html"),
                        tuple(answers.get(1), "a/index.html"));
    }

    @Test
    @DisplayName("답 메시지가 없는 turn 은 폴더에 HTML 이 있어도 묶지 않는다")
    void doesNotBindHtmlForTurnWithoutReplyMessage() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now());

        artifactService.recordTurn(conversation.id(), null, Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(artifactRows.findAll()).isEmpty();
    }

    @Test
    @DisplayName("HTML 은 200 이고 스크립트를 막는 머리글이 붙는다")
    void servesHtmlWith200AndScriptBlockingHeaders() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now());

        HttpResponse<String> response = file(conversation, "a/index.html");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(HTML);
        assertThat(header(response, "Content-Type")).startsWith("text/html").containsIgnoringCase("charset=utf-8");
        assertThat(header(response, "Content-Security-Policy")).isEqualTo(CSP);
        assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(header(response, "Cache-Control")).isEqualTo("private, no-cache");
    }

    @Test
    @DisplayName("한국어 폴더 이름의 파일도 준다")
    void servesFilesInKoreanFolderName() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "초안/index.html", HTML, Instant.now());

        HttpResponse<String> response = file(conversation, "초안/index.html");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(HTML);
    }

    @Test
    @DisplayName("행이 없는 사진도 폴더 안에 있으면 준다")
    void servesImageWithoutRowIfInsideFolder() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/photo.png", "png", Instant.now());

        HttpResponse<String> response = file(conversation, "a/photo.png");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(header(response, "Content-Type")).isEqualTo("image/png");
        assertThat(header(response, "Content-Security-Policy")).isEqualTo(CSP);
    }

    @Test
    @DisplayName("받지 않는 확장자는 파일이 있어도 ARTIFACT NOT FOUND 다")
    void unacceptedExtensionIsArtifactNotFoundEvenIfFileExists() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/x.svg", "<svg><script>alert(1)</script></svg>", Instant.now());

        HttpResponse<String> response = file(conversation, "a/x.svg");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("ARTIFACT_NOT_FOUND");
    }

    @Test
    @DisplayName("경로에 상위 폴더를 섞으면 폴더 밖 파일을 주지 않는다")
    void doesNotServeFilesOutsideFolderForPathWithParentSegments() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        store.ensureFolder(conversation.id());
        Path outside = root.resolve("other").resolve("a.html");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "SECRET");
        String base = "/api/v1/chat/conversations/" + conversation.publicId() + "/files/";

        // 방화벽이 거절한 요청은 /error 로 넘어가고, 그 경로가 인증에 걸려 403 으로 끝난다. 컨트롤러에는 닿지 않는다.
        for (String raw : List.of("../other/a.html", "%2e%2e/other/a.html", "a/../../other/a.html")) {
            HttpResponse<String> response = get(base + raw);
            assertThat(response.statusCode()).as(raw).isEqualTo(403);
            assertThat(response.body()).as(raw).doesNotContain("SECRET");
        }
    }

    @Test
    @DisplayName("상위 폴더를 가리키는 상대 경로를 직접 풀면 빈 값이다")
    void resolvingRelativePathToParentDirectlyIsEmpty() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        store.ensureFolder(conversation.id());
        Path outside = root.resolve("other").resolve("a.html");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "SECRET");

        assertThat(store.resolveInside(conversation.id(), "../other/a.html")).isEmpty();
    }

    @Test
    @DisplayName("폴더 밖을 가리키는 심볼릭 링크는 ARTIFACT NOT FOUND 다")
    void symlinkPointingOutsideFolderIsArtifactNotFound() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        Path outside = root.resolve("other").resolve("secret.html");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "SECRET");
        Path link = root.resolve(String.valueOf(conversation.id())).resolve("a").resolve("link.html");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);

        HttpResponse<String> response = file(conversation, "a/link.html");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("ARTIFACT_NOT_FOUND");
        assertThat(response.body()).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("대화 폴더 자체가 다른 대화의 폴더를 가리키는 링크면 ARTIFACT NOT FOUND 다")
    void conversationFolderLinkingToOtherConversationIsArtifactNotFound() throws Exception {
        Conversation other = chat.startEmpty(dad, "dad");
        writeAt(other.id(), "a/index.html", "SECRET", Instant.now());
        Conversation conversation = chat.startEmpty(dad, "dad");
        Path folder = root.resolve(String.valueOf(conversation.id()));
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(folder);
        }
        Files.createSymbolicLink(folder, root.resolve(String.valueOf(other.id())));

        HttpResponse<String> response = file(conversation, "a/index.html");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("ARTIFACT_NOT_FOUND");
        assertThat(response.body()).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("허용된 이름의 링크가 폴더 안의 사진을 가리키면 실제 파일의 형식으로 준다")
    void linkWithAllowedNameToImageInFolderServesRealFileType() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/b.png", "png", Instant.now());
        Path folder = root.resolve(String.valueOf(conversation.id())).resolve("a");
        Files.createSymbolicLink(folder.resolve("a.html"), folder.resolve("b.png"));

        HttpResponse<String> response = file(conversation, "a/a.html");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(header(response, "Content-Type")).isEqualTo("image/png");
    }

    @Test
    @DisplayName("읽지 못하는 하위 폴더가 있어도 나머지 HTML 은 찾고 오래된 파일은 지운다")
    void findsOtherHtmlAndDeletesOldFilesDespiteUnreadableSubfolder() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(1));
        writeAt(conversation.id(), "ok.html", HTML, Instant.now());
        Conversation stale = chat.startEmpty(dad, "dad");
        writeAt(stale.id(), "old/old.html", HTML, Instant.now().minus(Duration.ofDays(40)));
        writeAt(conversation.id(), "locked/hidden.html", HTML, Instant.now());
        Path locked = root.resolve(String.valueOf(conversation.id())).resolve("locked");
        // 권한을 빼도 root 로 도는 실행은 그 폴더를 읽는다. 그래서 읽지 못한 폴더의 파일이 빠졌는지는 단언하지 않고,
        // 나머지를 놓치지 않는다는 것만 본다. 어느 사용자로 돌아도 같은 단언이 성립한다.
        Files.setPosixFilePermissions(locked, Set.of());
        try {
            assertThat(store.changedHtmlSince(conversation.id(), startedAt))
                    .extracting(ArtifactStore.FoundFile::path)
                    .contains("ok.html");

            List<ArtifactStore.Removed> removed = store.deleteOlderThan(Instant.now().minus(Duration.ofDays(30)));

            assertThat(removed).extracting(ArtifactStore.Removed::path).containsExactly("old/old.html");
            assertThat(Files.exists(root.resolve(String.valueOf(conversation.id())).resolve("ok.html"))).isTrue();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("폴더에 최근에 바뀐 파일이 있으면 그 폴더의 오래된 사진도 지우지 않는다")
    void keepsOldImagesInFolderWithRecentlyChangedFile() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/photo.png", "png", Instant.now().minus(Duration.ofDays(40)));
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now());

        List<ArtifactStore.Removed> removed = store.deleteOlderThan(Instant.now().minus(Duration.ofDays(30)));

        assertThat(removed).isEmpty();
        assertThat(Files.exists(root.resolve(String.valueOf(conversation.id())).resolve("a").resolve("photo.png")))
                .isTrue();
    }

    @Test
    @DisplayName("남의 대화의 파일은 CONVERSATION NOT FOUND 다")
    void otherUsersConversationFileIsConversationNotFound() throws Exception {
        CurrentUser kid = member("artifact-kid@example.com");
        agentOf(kid, "kid", null);
        Conversation theirs = chat.startEmpty(kid, "kid");
        writeAt(theirs.id(), "a/index.html", HTML, Instant.now());

        HttpResponse<String> response = file(theirs, "a/index.html");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(response.body()).doesNotContain("초안");
    }

    @Test
    @DisplayName("파일 응답에는 약한 ETag 와 Last Modified 가 붙는다")
    void fileResponseCarriesWeakEtagAndLastModified() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now());

        HttpResponse<String> response = file(conversation, "a/index.html");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, "ETag")).startsWith("W/\"");
        assertThat(header(response, "Last-Modified")).isNotEmpty();
    }

    @Test
    @DisplayName("받은 ETag 로 다시 물으면 본문 없이 304 이고 보호 머리글이 그대로다")
    void revalidatingWithEtagReturns304WithoutBodyKeepingProtectiveHeaders() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now());
        String etag = header(file(conversation, "a/index.html"), "ETag");

        HttpResponse<String> response = file(conversation, "a/index.html", dad, "If-None-Match", etag);

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
        assertThat(header(response, "ETag")).isEqualTo(etag);
        assertThat(header(response, "Cache-Control")).isEqualTo("private, no-cache");
        assertThat(header(response, "Content-Security-Policy")).isEqualTo(CSP);
        assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("받은 Last Modified 로 다시 물으면 304 다")
    void revalidatingWithLastModifiedReturns304() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now());
        String modified = header(file(conversation, "a/index.html"), "Last-Modified");

        HttpResponse<String> response = file(conversation, "a/index.html", dad, "If-Modified-Since", modified);

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("파일이 바뀐 뒤 옛 ETag 로 물으면 200 과 새 본문이다")
    void oldEtagAfterFileChangeReturns200WithNewBody() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        Instant first = Instant.now().minus(Duration.ofMinutes(5));
        writeAt(conversation.id(), "a/index.html", HTML, first);
        String oldEtag = header(file(conversation, "a/index.html"), "ETag");
        writeAt(conversation.id(), "a/index.html", "<p>고친 본문</p>", first.plus(Duration.ofMinutes(1)));

        HttpResponse<String> response = file(conversation, "a/index.html", dad, "If-None-Match", oldEtag);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("<p>고친 본문</p>");
        assertThat(header(response, "ETag")).isNotEqualTo(oldEtag);
    }

    @Test
    @DisplayName("If None Match 가 있으면 If Modified Since 는 보지 않는다")
    void ignoresIfModifiedSinceWhenIfNoneMatchPresent() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now().minus(Duration.ofMinutes(5)));
        String future = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneOffset.UTC).plusDays(1));

        HttpResponse<String> response = file(conversation, "a/index.html", dad,
                "If-None-Match", "\"other\"", "If-Modified-Since", future);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(HTML);
    }

    @Test
    @DisplayName("다른 사용자가 맞는 ETag 를 보내도 CONVERSATION NOT FOUND 다")
    void otherUserWithMatchingEtagIsConversationNotFound() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        writeAt(conversation.id(), "a/index.html", HTML, Instant.now());
        String etag = header(file(conversation, "a/index.html"), "ETag");
        CurrentUser kid = member("artifact-kid@example.com");
        agentOf(kid, "kid", null);

        HttpResponse<String> response = file(conversation, "a/index.html", kid, "If-None-Match", etag);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    @DisplayName("보관 기간이 지나 지운 HTML 은 행에 지운 시각이 남고 410 이다")
    void expiredDeletedHtmlRecordsDeletionTimeAndReturns410() throws Exception {
        Conversation conversation = chat.startEmpty(dad, "dad");
        stub().beforeAwait(() -> writeDuringTurn(conversation.id(), "a/index.html", HTML));
        chat.send(dad, conversation.id(), "만들어 줘", null);
        Path html = root.resolve(String.valueOf(conversation.id())).resolve("a").resolve("index.html");
        Files.setLastModifiedTime(html, FileTime.from(Instant.now().minus(Duration.ofDays(31))));

        int removed = cleaner.cleanExpired(Instant.now());

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(html)).isFalse();
        assertThat(artifactRows.findAll()).singleElement()
                .satisfies(row -> assertThat(row.deletedAt()).isNotNull());
        HttpResponse<String> response = file(conversation, "a/index.html");
        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(code(response)).isEqualTo("ARTIFACT_GONE");
        assertThat(answerArtifacts(conversation))
                .containsExactly(List.of("a/index.html", String.valueOf(htmlBytes()), "true"));
    }

    /** 대화 이력의 답마다 붙은 결과물을 {@code [path, byteSize, deleted]} 로 모은다. */
    private List<List<String>> answerArtifacts(Conversation conversation) throws Exception {
        HttpResponse<String> response =
                get("/api/v1/chat/conversations/" + conversation.publicId() + "/messages");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode message : json.readTree(response.body())) {
            if (!"ASSISTANT".equals(message.path("role").asString())) {
                assertThat(message.path("artifacts").isEmpty()).as("사용자 메시지에는 결과물이 없다").isTrue();
                continue;
            }
            for (JsonNode artifact : message.path("artifacts")) {
                rows.add(List.of(
                        artifact.path("path").asString(),
                        String.valueOf(artifact.path("byteSize").asLong()),
                        String.valueOf(artifact.path("deleted").asBoolean())));
            }
        }
        return rows;
    }

    private static int htmlBytes() {
        return HTML.getBytes(StandardCharsets.UTF_8).length;
    }

    private List<Long> assistantMessageIds(Long conversationId) {
        return messages.findByConversationIdOrderByIdAsc(conversationId).stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT)
                .map(ChatMessage::id)
                .toList();
    }

    /**
     * turn 이 도는 사이 에이전트가 쓴 것처럼 파일을 둔다.
     *
     * <p>수정 시각을 지금으로 못 박는다. 커널의 파일 시각은 JVM 의 시계보다 조금 늦게 갈 수 있어, 그대로 두면 turn
     * 시작보다 앞선 시각이 찍혀 검사가 흔들린다.
     */
    private void writeDuringTurn(Long conversationId, String relativePath, String content) {
        try {
            writeAt(conversationId, relativePath, content, Instant.now());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void writeAt(Long conversationId, String relativePath, String content, Instant modified)
            throws IOException {
        Path file = root.resolve(String.valueOf(conversationId)).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private HttpResponse<String> file(Conversation conversation, String relativePath) throws Exception {
        return file(conversation, relativePath, dad);
    }

    /** 보내는 사용자와 요청 머리글(이름, 값 순서의 쌍)을 정해 파일을 받는다. */
    private HttpResponse<String> file(
            Conversation conversation, String relativePath, CurrentUser sender, String... headers)
            throws Exception {
        String encoded = StreamSupport.stream(Path.of(relativePath).spliterator(), false)
                .map(segment -> URLEncoder.encode(segment.toString(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "/" + right)
                .orElseThrow();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/chat/conversations/" + conversation.publicId() + "/files/" + encoded))
                .header("Authorization", "Bearer " + jwt(sender))
                .GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwt(dad))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwt(dad))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private String code(HttpResponse<String> response) {
        return json.readTree(response.body()).path("code").asString();
    }

    private String jwt(CurrentUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.email())
                .claim("name", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    private static HermesRunResult completed(String runId, String output) {
        return HermesRunResult.of(runId, "sess-" + runId, "completed", output, "m", "p", TokenUsage.empty());
    }

    private CurrentUser member(String email) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private void agentOf(CurrentUser owner, String code, String flow) {
        Agent agent = Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id());
        if (flow != null) {
            agent.assignFlow(flow);
        }
        agents.save(agent);
    }

    /** 링크를 만드는 테스트가 남긴 것이 저장 root 를 함께 쓰는 다른 테스트 클래스에 걸리지 않게 비운다. */
    @AfterEach
    void tearDown() throws IOException {
        deleteTree(root);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
