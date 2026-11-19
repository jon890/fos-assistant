package com.bifos.assistant.chat.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactKept;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.config.ArtifactVaultProperties;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.VaultLabel;
import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultBodies;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultMeta;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultWriter;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.domain.SealedText;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
 * 바이트로 받은 파일 하나를 「내 파일」 에 넣는 길을 확인한다(스펙 workspace REQ4, REQ5, REQ6).
 *
 * <p>받는 형식과 상한, 제목의 겹침과 암호화, 같은 파일의 되풀이, 실행 공간과 대화 폴더의 경로 나눔을 본다.
 */
@BackendIntegrationTest
class ArtifactVaultFileTypeTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final Instant NOW = Instant.parse("2026-11-19T06:00:00Z");
    private static final String TITLE = "평문-제목-표식-4412";

    @LocalServerPort
    int port;

    @Autowired
    ArtifactVaultService vault;

    @Autowired
    ArtifactBundleReader reader;

    @Autowired
    ArtifactVaultWriter writer;

    @Autowired
    ArtifactVaultBodies bodies;

    @Autowired
    ArtifactVaultTitles titles;

    @Autowired
    ArtifactVaultMeta metas;

    @Autowired
    ArtifactStore store;

    @Autowired
    ChatArtifactRepository rows;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newHttpClient();
    private AppUser mom;
    private AppUser dad;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        mom = member("kept-mom-" + UUID.randomUUID());
        dad = member("kept-dad-" + UUID.randomUUID());
        conversation = conversations.save(Conversation.startedBy(dad.id(), "가계부", null, NOW));
    }


    @AfterEach
    void tearDown() {
        rows.deleteAll(rows.findAll().stream().filter(row -> row.conversationId().equals(conversation.id())).toList());
        store.deleteFolder(conversation.id());
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("엑셀 파일을 넣으면 주인만 같은 바이트로 읽고 본문은 내려받기로만 나간다")
    void keepsSpreadsheet() throws Exception {
        byte[] xlsx = zip(2048);

        ArtifactKept kept = keep(VaultOrigin.PUT_WORKSPACE, "out/가계부.xlsx", xlsx, "11월 가계부", "11월 지출을 항목별로 모은 표");

        assertThat(kept.already()).isFalse();
        assertThat(vault.read(dad.id(), kept.item().id()).body()).isEqualTo(xlsx);
        assertThatThrownBy(() -> vault.read(mom.id(), kept.item().id()))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_NOT_FOUND));
        HttpResponse<byte[]> response = get("/api/v1/artifact-vault/" + kept.item().id() + "/content", dad);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(xlsx);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/octet-stream");
        assertThat(get("/api/v1/artifact-vault/" + kept.item().id() + "/content", mom).statusCode()).isEqualTo(404);
    }


    @Test
    @DisplayName("목록 응답에 제목과 설명, 종류가 있고 DB 에는 평문 제목이 없다")
    void listsTitleAndKeepsItEncrypted() throws Exception {
        ArtifactKept kept = keep(VaultOrigin.PUT_WORKSPACE, "a.csv", csv("가"), TITLE, "설명 한 줄");

        ArtifactVaultItem listed = vault.list(dad.id()).stream()
            .filter(item -> item.id().equals(kept.item().id()))
            .findFirst()
            .orElseThrow();
        assertThat(listed.title()).isEqualTo(TITLE);
        assertThat(listed.description()).isEqualTo("설명 한 줄");
        assertThat(listed.kind()).isEqualTo("TABLE");
        String stored = jdbc.queryForObject("select vault_meta from chat_artifact where id = ?", String.class, kept.item().id());
        assertThat(stored).isNotBlank().doesNotContain(TITLE);
        String body = new String(get("/api/v1/artifact-vault", dad).body(), StandardCharsets.UTF_8);
        assertThat(body).contains(TITLE).contains("\"kind\":\"TABLE\"");
    }


    @Test
    @DisplayName("같은 대화에서 제목이 겹치면 번호를 붙이고 같은 파일을 고쳐 넣으면 번호가 늘지 않는다")
    void numbersDuplicateTitles() {
        ArtifactKept first = keep(VaultOrigin.PUT_WORKSPACE, "a.csv", csv("가"), "11월 가계부", null);
        ArtifactKept second = keep(VaultOrigin.PUT_WORKSPACE, "b.csv", csv("나"), "11월 가계부", null);
        ArtifactKept edited = keep(VaultOrigin.PUT_WORKSPACE, "a.csv", csv("가 고침"), "11월 가계부", null);
        ArtifactKept untitled = keep(VaultOrigin.PUT_WORKSPACE, "c.csv", csv("다"), null, null);

        assertThat(first.item().title()).isEqualTo("11월 가계부");
        assertThat(second.item().title()).isEqualTo("11월 가계부 2");
        // 같은 경로의 앞 보관본은 겹침에서 빼므로 고친 파일은 제 제목을 그대로 쓴다.
        assertThat(edited.item().title()).isEqualTo("11월 가계부");
        assertThat(untitled.item().title()).isNull();
    }


    @Test
    @DisplayName("같은 파일을 두 번 넣으면 한 건이고 실행 공간과 대화 폴더의 같은 경로는 다른 건이다")
    void dedupesAndSeparatesOrigins() {
        byte[] body = csv("같은 본문");

        ArtifactKept first = keep(VaultOrigin.PUT_WORKSPACE, "same.csv", body, "하나", null);
        ArtifactKept again = keep(VaultOrigin.PUT_WORKSPACE, "./same.csv", body, "다른 제목", null);
        ArtifactKept folder = keep(VaultOrigin.PUT, "same.csv", body, null, null);

        assertThat(again.already()).isTrue();
        assertThat(again.item().id()).isEqualTo(first.item().id());
        assertThat(again.item().title()).isEqualTo("하나");
        assertThat(folder.already()).isFalse();
        assertThat(folder.item().id()).isNotEqualTo(first.item().id());
        assertThat(vault.list(dad.id())).hasSize(2);
    }


    @Test
    @DisplayName("받지 않는 형식과 형식에 맞지 않는 본문, 빈 파일은 거절하고 아무것도 남기지 않는다")
    void rejectsUnsupported() {
        assertRejected(() -> keep(VaultOrigin.PUT_WORKSPACE, "a.zip", zip(64), null, null), "this kind of file is not kept");
        assertRejected(() -> keep(VaultOrigin.PUT_WORKSPACE, "run.sh", csv("echo"), null, null), "this kind of file is not kept");
        assertRejected(() -> keep(VaultOrigin.PUT_WORKSPACE, "a.xlsx", csv("표가 아니다"), null, null), "this file does not match its kind");
        assertRejected(() -> keep(VaultOrigin.PUT_WORKSPACE, "a.csv", new byte[0], null, null), "this file is empty");
        assertThat(vault.list(dad.id())).isEmpty();
    }


    @Test
    @DisplayName("파일 하나는 기본 25MiB 까지이고 상한의 파일은 들어가며 1바이트 크면 거절된다")
    void enforcesFileLimit() {
        assertThat(ArtifactVaultProperties.defaults().maxFileBytes()).isEqualTo(25L * 1024 * 1024);
        ArtifactVaultService small = service(new ArtifactVaultProperties(null, 4096L, null, null, null));

        ArtifactKept atLimit = small.keep(
            dad.id(), conversation.id(), VaultOrigin.PUT_WORKSPACE, "limit.xlsx", zip(4096), null, null, null);

        assertThat(vault.read(dad.id(), atLimit.item().id()).body()).hasSize(4096);
        assertRejected(
            () -> small.keep(dad.id(), conversation.id(), VaultOrigin.PUT_WORKSPACE, "over.xlsx", zip(4097), null, null, null),
            "this file is larger than the vault limit");
    }


    @Test
    @DisplayName("용량이 차면 새 파일만 거절하고 있던 파일은 그대로 열린다")
    void rejectsOnlyNewFileOverQuota() {
        ArtifactVaultService small = service(new ArtifactVaultProperties(100L, null, null, null, null));
        ArtifactKept first = small.keep(
            dad.id(), conversation.id(), VaultOrigin.PUT_WORKSPACE, "a.csv", "a".repeat(60).getBytes(), null, null, null);

        assertThatThrownBy(() -> small.keep(
            dad.id(), conversation.id(), VaultOrigin.PUT_WORKSPACE, "b.csv", "b".repeat(60).getBytes(), null, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_VAULT_FULL));

        assertThat(small.read(dad.id(), first.item().id()).body()).hasSize(60);
        assertThat(small.list(dad.id())).hasSize(1);
    }


    @Test
    @DisplayName("지우면 제목도 사라지고 다시 넣으면 새로 생긴다")
    void forgetsTitleAndRevives() {
        byte[] body = csv("지울 것");
        ArtifactKept kept = keep(VaultOrigin.PUT_WORKSPACE, "gone.csv", body, TITLE, null);

        vault.delete(dad.id(), kept.item().id());

        assertThat(jdbc.queryForObject(
            "select count(*) from chat_artifact where id = ? and vault_meta is null and vault_meta_key_id is null and deleted_at is not null",
            Integer.class, kept.item().id())).isEqualTo(1);
        ArtifactKept again = keep(VaultOrigin.PUT_WORKSPACE, "gone.csv", body, TITLE, null);
        assertThat(again.already()).isFalse();
        assertThat(vault.read(dad.id(), again.item().id()).body()).isEqualTo(body);
    }


    @Test
    @DisplayName("artifact_write 가 결과물 행에 둔 제목이 보관본의 제목이 되고 넣을 때 준 제목이 그보다 앞선다")
    void carriesWrittenTitle() {
        store.write(conversation.id(), "report/index.html", "<!doctype html><p>보고서</p>".getBytes(StandardCharsets.UTF_8));
        SealedText sealed = metas
            .seal(dad.id(), conversation.id(), "report/index.html", VaultLabel.of("주간 보고서", "이번 주 요약").orElseThrow())
            .orElseThrow();
        rows.save(ChatArtifact.titled(conversation.id(), "report/index.html", 10, sealed.content(), sealed.keyId()));

        ArtifactVaultItem vaulted = vault.vault(dad.id(), conversation.id(), "report/index.html");

        assertThat(vaulted.title()).isEqualTo("주간 보고서");
        assertThat(vaulted.description()).isEqualTo("이번 주 요약");
        vault.delete(dad.id(), vaulted.id());
        ArtifactKept put = vault.keepBundle(dad.id(), conversation.id(), "report/index.html", "넣을 때 준 제목", null, null);
        assertThat(put.item().title()).isEqualTo("넣을 때 준 제목");
    }


    private ArtifactKept keep(VaultOrigin origin, String path, byte[] bytes, String title, String description) {
        return vault.keep(dad.id(), conversation.id(), origin, path, bytes, title, description, null);
    }


    private ArtifactVaultService service(ArtifactVaultProperties limits) {
        return new ArtifactVaultService(reader, rows, writer, bodies, titles, metas, limits, transactions, Clock.systemUTC());
    }


    private static void assertRejected(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED);
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }


    /** zip 머리 바이트로 시작하는 {@code size} 바이트다. 엑셀 파일의 대역이다. */
    private static byte[] zip(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte)'x');
        bytes[0] = 'P';
        bytes[1] = 'K';
        bytes[2] = 3;
        bytes[3] = 4;
        return bytes;
    }


    private static byte[] csv(String value) {
        return ("이름,값\n" + value + ",1\n").getBytes(StandardCharsets.UTF_8);
    }


    private HttpResponse<byte[]> get(String path, AppUser sender) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + jwt(sender))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
