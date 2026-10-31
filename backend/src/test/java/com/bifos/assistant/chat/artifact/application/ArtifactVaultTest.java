package com.bifos.assistant.chat.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultContent;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.config.ArtifactProperties;
import com.bifos.assistant.chat.artifact.config.ArtifactVaultProperties;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultBodies;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultWriter;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.application.ConversationWriter;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.purge.application.ConversationPurger;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 고른 HTML·CSS 를 사용자별 보관함에 암호화해 두고 주인에게만 읽고 지우게 하는지 확인한다(#530 PR 1).
 *
 * <p>묶음 보관은 {@code ArtifactVaultBundleTest} 가 본다.
 *
 * <p>남의 보관본, 대화 삭제 뒤의 보관본, 용량 초과, 위변조, 같은 요청의 중복을 본다.
 */
@BackendIntegrationTest
class ArtifactVaultTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final Instant NOW = Instant.parse("2026-10-30T06:00:00Z");
    private static final String MARKER = "평문-표식-보관-7731";
    private static final String HTML = "<!doctype html><title>초안</title><p>" + MARKER + "</p>";

    @LocalServerPort
    int port;

    @Autowired
    ArtifactVaultService vault;

    @Autowired
    ArtifactBundleReader reader;

    @Autowired
    ArtifactVaultWriter writer;

    @Autowired
    ArtifactStore store;

    @Autowired
    ArtifactProperties properties;

    @Autowired
    ChatArtifactRepository rows;

    @Autowired
    ArtifactVaultBodies bodies;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ConversationPurger purger;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newHttpClient();
    private AppUser mom;
    private AppUser dad;
    private Conversation dadConversation;


    @BeforeEach
    void setUp() {
        mom = member("vault-mom-" + UUID.randomUUID());
        dad = member("vault-dad-" + UUID.randomUUID());
        dadConversation = conversations.save(Conversation.startedBy(dad.id(), "초안", null, NOW));
        write(dadConversation, "draft/index.html", HTML);
    }


    @AfterEach
    void tearDown() {
        rows.deleteAll(rows.findAll().stream().filter(row -> row.isVaulted()).toList());
        store.deleteFolder(dadConversation.id());
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("보관한 본문은 데이터베이스에 암호문으로 남고 주인이 읽으면 평문이다")
    void keepsCiphertextAndOwnerReadsPlaintext() {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");

        String stored = jdbc.queryForObject(
            "select vault_body from chat_artifact where id = ?", String.class, item.id());
        assertThat(stored).startsWith("v1.").doesNotContain(MARKER);
        ArtifactVaultContent content = vault.read(dad.id(), item.id());
        assertThat(new String(content.body(), StandardCharsets.UTF_8)).isEqualTo(HTML);
        assertThat(content.contentType()).isEqualTo("text/html");
        assertThat(vault.list(dad.id())).extracting(ArtifactVaultItem::id).containsExactly(item.id());
    }


    @Test
    @DisplayName("남은 남의 보관본을 읽지도 지우지도 못하고 목록에도 보지 못한다")
    void otherUserCannotReadDeleteOrListSomeoneElsesVault() throws Exception {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");

        assertThatThrownBy(() -> vault.read(mom.id(), item.id()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_NOT_FOUND));
        assertThatThrownBy(() -> vault.delete(mom.id(), item.id()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_NOT_FOUND));
        assertThat(vault.list(mom.id())).isEmpty();
        assertThat(http("GET", "/api/v1/artifact-vault/" + item.id() + "/content", mom, null).statusCode())
            .isEqualTo(404);
        assertThat(http("DELETE", "/api/v1/artifact-vault/" + item.id(), mom, null).statusCode()).isEqualTo(404);
        assertThat(vault.read(dad.id(), item.id()).body()).isNotEmpty();
    }


    @Test
    @DisplayName("남의 대화의 결과물은 보관하지 못한다")
    void cannotVaultSomeoneElsesConversation() throws Exception {
        String body = "{\"conversationId\":\"" + dadConversation.publicId() + "\",\"path\":\"draft/index.html\"}";

        HttpResponse<String> response = http("POST", "/api/v1/artifact-vault", mom, body);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(vault.list(mom.id())).isEmpty();
    }


    @Test
    @DisplayName("주인은 HTTP 로 보관하고 읽고 지운다. 본문은 스크립트 없는 머리글과 함께 온다")
    void ownerVaultsReadsAndDeletesOverHttp() throws Exception {
        String body = "{\"conversationId\":\"" + dadConversation.publicId() + "\",\"path\":\"draft/index.html\"}";

        HttpResponse<String> created = http("POST", "/api/v1/artifact-vault", dad, body);
        assertThat(created.statusCode()).isEqualTo(200);
        Long id = vault.list(dad.id()).getFirst().id();
        HttpResponse<String> content = http("GET", "/api/v1/artifact-vault/" + id + "/content", dad, null);
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).isEqualTo(HTML);
        assertThat(content.headers().firstValue("Content-Security-Policy")).get().asString().startsWith("sandbox");
        assertThat(content.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(http("DELETE", "/api/v1/artifact-vault/" + id, dad, null).statusCode()).isEqualTo(204);
        assertThat(http("GET", "/api/v1/artifact-vault/" + id + "/content", dad, null).statusCode())
            .isEqualTo(404);
    }


    @Test
    @DisplayName("대화를 지워 정리해도 보관본은 남고 대화 결과물 행만 사라진다")
    void vaultSurvivesConversationPurge() {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");
        conversationWriter.deleteIfActive(dadConversation.id(), dad.id(), NOW.minusSeconds(60));

        purger.purgeDue(NOW);

        assertThat(Path.of(properties.root()).toAbsolutePath().resolve(String.valueOf(dadConversation.id())))
            .doesNotExist();
        assertThat(rows.findByIdAndVaultUserId(item.id(), dad.id())).isPresent();
        assertThat(new String(vault.read(dad.id(), item.id()).body(), StandardCharsets.UTF_8)).isEqualTo(HTML);
    }


    @Test
    @DisplayName("용량을 넘으면 새 보관만 거절하고 이미 보관한 것은 그대로이며 지우면 다시 보관할 수 있다")
    void rejectsOnlyNewVaultOverQuota() {
        ArtifactVaultService small = new ArtifactVaultService(
            reader, rows, writer, bodies, new ArtifactVaultProperties(100L, null, null, null), transactions);
        write(dadConversation, "a.css", "a".repeat(60));
        write(dadConversation, "b.css", "b".repeat(60));

        ArtifactVaultItem first = small.vault(dad.id(), dadConversation.id(), "a.css");
        assertThatThrownBy(() -> small.vault(dad.id(), dadConversation.id(), "b.css"))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_VAULT_FULL));

        assertThat(small.list(dad.id())).extracting(ArtifactVaultItem::id).containsExactly(first.id());
        assertThat(small.read(dad.id(), first.id()).body()).hasSize(60);
        small.delete(dad.id(), first.id());
        assertThat(small.vault(dad.id(), dadConversation.id(), "b.css").byteSize()).isEqualTo(60);
        // 용량은 사용자마다 센다. 다른 사용자의 보관은 막히지 않는다.
        Conversation momConversation = conversations.save(Conversation.startedBy(mom.id(), "엄마", null, NOW));
        write(momConversation, "c.css", "c".repeat(60));
        assertThat(small.vault(mom.id(), momConversation.id(), "c.css").byteSize()).isEqualTo(60);
        store.deleteFolder(momConversation.id());
    }


    @Test
    @DisplayName("같은 대화·경로·본문을 다시 보관해도 한 건이고 내용이 바뀌면 새 보관본이다")
    void sameRequestIsOneRowAndChangedContentIsNewRow() {
        ArtifactVaultItem first = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");
        ArtifactVaultItem again = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(vault.list(dad.id())).hasSize(1);

        write(dadConversation, "draft/index.html", HTML + "<p>고침</p>");
        ArtifactVaultItem changed = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");

        assertThat(changed.id()).isNotEqualTo(first.id());
        assertThat(new String(vault.read(dad.id(), first.id()).body(), StandardCharsets.UTF_8)).isEqualTo(HTML);
    }


    @Test
    @DisplayName("받지 않는 형식이거나 부르는 파일이 없거나 상한을 넘으면 보관하지 않는다")
    void rejectsUnsupportedFiles() {
        write(dadConversation, "withimg.html", "<p><img src=\"a.png\"></p>");
        write(dadConversation, "withfe.html", "<svg><feImage href=\"a.png\"/></svg>");
        write(dadConversation, "withbg.html", "<body background=\"a.jpg\">");
        write(dadConversation, "withurl.css", "p{background:url(a.png)}");
        write(dadConversation, "big.css", "x".repeat(6 * 1024 * 1024));

        for (String path : new String[] {"notes.txt", "withimg.html", "withfe.html", "withbg.html", "withurl.css", "big.css"}) {
            assertThatThrownBy(() -> vault.vault(dad.id(), dadConversation.id(), path))
                .as(path)
                .isInstanceOfSatisfying(
                    ApiException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED));
        }
        assertThat(vault.list(dad.id())).isEmpty();
    }


    @Test
    @DisplayName("암호문을 고치거나 남의 key 번호로 바꾸면 본문 일부도 주지 않는다")
    void tamperedCiphertextYieldsNothing() {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");
        String stored = jdbc.queryForObject(
            "select vault_body from chat_artifact where id = ?", String.class, item.id());
        String broken = stored.substring(0, stored.length() - 4) + (stored.endsWith("AAAA") ? "BBBB" : "AAAA");
        jdbc.update("update chat_artifact set vault_body = ? where id = ?", broken, item.id());

        assertThatThrownBy(() -> vault.read(dad.id(), item.id()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_VAULT_UNAVAILABLE));
    }


    @Test
    @DisplayName("보관본을 다른 사용자의 줄로 옮기면 풀리지 않는다")
    void movedCiphertextToAnotherUserDoesNotOpen() {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "draft/index.html");
        jdbc.update("update chat_artifact set vault_user_id = ? where id = ?", mom.id(), item.id());

        assertThatThrownBy(() -> vault.read(mom.id(), item.id()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_VAULT_UNAVAILABLE));
    }


    private HttpResponse<String> http(String method, String path, AppUser sender, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + jwt(sender));
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }


    private void write(Conversation conversation, String path, String content) {
        store.write(conversation.id(), path, content.getBytes(StandardCharsets.UTF_8));
    }


    private AppUser member(String name) {
        String email = name + "@example.test";
        return users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
    }


    private String jwt(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.email())
            .claim("name", user.displayName())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(Duration.ofMinutes(10))))
            .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }
}
