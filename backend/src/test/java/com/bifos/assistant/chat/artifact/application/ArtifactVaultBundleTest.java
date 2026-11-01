package com.bifos.assistant.chat.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactBundle;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 사진과 스타일을 부르는 HTML 을 묶음으로 보관하고, 사진 하나도 보관하는지 확인한다(#530 PR 2).
 *
 * <p>상대 주소 따라가기, 부르지 않는 파일 제외, 부분 묶음 금지, 남의 묶음 차단, 대화 삭제 뒤 보존, 상한과 용량, 보관 도중의
 * 원본 변경, 동시 요청 상한을 본다.
 */
@BackendIntegrationTest
class ArtifactVaultBundleTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");
    private static final String PAGE = """
        <!doctype html><html><head>
        <link rel="stylesheet" href="css/site.css?v=2">
        <link rel="canonical" href="https://example.test/page">
        <style>.hero{background:url('img/hero.png')}</style>
        </head><body>
        <a href="https://example.test/elsewhere">링크</a>
        <img src="img/a%20b.png" srcset="img/a%20b.png 1x, img/big.png 2x" alt="사진">
        <script src="https://example.test/never-runs.js"></script>
        <svg><use href="#icon"/></svg>
        </body></html>
        """;
    private static final String SITE_CSS = "@import \"base.css\";\nbody{background:url(../img/back.webp)} /* url(no.png) */";
    private static final byte[] PNG = {(byte)0x89, 'P', 'N', 'G', 1, 2, 3, 4};

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
    ArtifactService artifacts;

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
    private final List<Long> folders = new ArrayList<>();
    private AppUser mom;
    private AppUser dad;
    private Conversation dadConversation;


    @BeforeEach
    void setUp() {
        mom = member("bundle-mom-" + UUID.randomUUID());
        dad = member("bundle-dad-" + UUID.randomUUID());
        dadConversation = conversation(dad, "묶음");
        write(dadConversation, "page/index.html", PAGE);
        write(dadConversation, "page/css/site.css", SITE_CSS);
        write(dadConversation, "page/css/base.css", "p{color:#333}");
        write(dadConversation, "page/img/hero.png", PNG);
        write(dadConversation, "page/img/a b.png", PNG);
        write(dadConversation, "page/img/big.png", PNG);
        write(dadConversation, "page/img/back.webp", PNG);
        write(dadConversation, "page/img/unused.png", PNG);
    }


    @AfterEach
    void tearDown() {
        rows.deleteAll(rows.findAll().stream().filter(row -> row.isVaulted()).toList());
        folders.forEach(store::deleteFolder);
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("HTML 이 상대 주소로 부르는 CSS 와 사진을 한 묶음으로 보관하고 부르지 않는 파일은 넣지 않는다")
    void vaultsReferencedFilesOnly() {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "page/index.html");

        List<String> paths = jdbc.queryForList(
            "select path from chat_artifact where vault_bundle_id = ? order by path", String.class, item.id());
        assertThat(paths).containsExactly(
            "page/css/base.css", "page/css/site.css", "page/img/a b.png", "page/img/back.webp", "page/img/big.png",
            "page/img/hero.png");
        assertThat(item.fileCount()).isEqualTo(7);
        long expected = PAGE.getBytes(StandardCharsets.UTF_8).length
            + SITE_CSS.getBytes(StandardCharsets.UTF_8).length + "p{color:#333}".length() + 4L * PNG.length;
        assertThat(item.byteSize()).isEqualTo(expected);
        assertThat(rows.sumVaultBytes(dad.id())).isEqualTo(expected);
        assertThat(vault.list(dad.id())).singleElement().satisfies(listed -> {
            assertThat(listed.id()).isEqualTo(item.id());
            assertThat(listed.fileCount()).isEqualTo(7);
            assertThat(listed.byteSize()).isEqualTo(expected);
        });
        List<String> stored = jdbc.queryForList(
            "select vault_body from chat_artifact where vault_bundle_id = ?", String.class, item.id());
        assertThat(stored).allSatisfy(body -> assertThat(body).startsWith("v1."));
        assertThat(vault.readFile(dad.id(), item.id(), "page/img/a b.png").body()).isEqualTo(PNG);
        assertThat(vault.readFile(dad.id(), item.id(), "page/img/a b.png").contentType()).isEqualTo("image/png");
        assertThat(new String(vault.readFile(dad.id(), item.id(), "page/css/site.css").body(), StandardCharsets.UTF_8))
            .isEqualTo(SITE_CSS);
        assertThatThrownBy(() -> vault.readFile(dad.id(), item.id(), "page/img/unused.png"))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_NOT_FOUND));
    }


    @Test
    @DisplayName("외부 주소나 data 주소, 폴더 밖 주소, 빠진 파일이 하나라도 있으면 묶음 전체를 보관하지 않는다")
    void rejectsWholeBundleOnBadReference() {
        write(dadConversation, "bad/external.html", "<img src=\"ok.png\"><img src=\"https://example.test/a.png\">");
        write(dadConversation, "bad/protocol.html", "<img src=\"//example.test/a.png\">");
        write(dadConversation, "bad/data.html", "<img src=\"data:image/png;base64,AAAA\">");
        write(dadConversation, "bad/absolute.html", "<img src=\"/etc/a.png\">");
        write(dadConversation, "bad/escape.html", "<img src=\"../../a.png\">");
        write(dadConversation, "bad/missing.html", "<img src=\"ok.png\"><img src=\"gone.png\">");
        write(dadConversation, "bad/css-external.html", "<link rel=stylesheet href=\"ext.css\">");
        write(dadConversation, "bad/ext.css", "@import url(https://example.test/x.css);");
        write(dadConversation, "bad/font.html", "<style>@font-face{src:url(f.woff2)}</style>");
        write(dadConversation, "bad/ok.png", PNG);

        for (String path : List.of(
            "bad/external.html", "bad/protocol.html", "bad/data.html", "bad/absolute.html", "bad/escape.html",
            "bad/missing.html", "bad/css-external.html", "bad/font.html")) {
            assertThatThrownBy(() -> vault.vault(dad.id(), dadConversation.id(), path))
                .as(path)
                .isInstanceOfSatisfying(
                    ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED));
        }
        assertThat(vaultedRowCount(dad)).isZero();
    }


    @Test
    @DisplayName("파일 하나, 묶음 전체, 파일 수 상한과 사용자 용량을 넘으면 묶음 전체를 거절한다")
    void rejectsBundleOverLimits() {
        write(dadConversation, "limit/one.html", "<img src=\"big.png\">");
        write(dadConversation, "limit/big.png", new byte[64]);
        write(dadConversation, "limit/many.html", "<img src=\"a.png\"><img src=\"b.png\"><img src=\"c.png\">");
        write(dadConversation, "limit/a.png", new byte[8]);
        write(dadConversation, "limit/b.png", new byte[8]);
        write(dadConversation, "limit/c.png", new byte[8]);

        ArtifactVaultService fileLimit = service(new ArtifactVaultProperties(null, 32L, null, null, null));
        ArtifactVaultService bundleLimit = service(new ArtifactVaultProperties(null, null, 60L, null, null));
        ArtifactVaultService countLimit = service(new ArtifactVaultProperties(null, null, null, 3, null));
        ArtifactVaultService quota = service(new ArtifactVaultProperties(60L, null, null, null, null));

        assertRejected(() -> fileLimit.vault(dad.id(), dadConversation.id(), "limit/one.html"),
            ErrorCode.ARTIFACT_VAULT_UNSUPPORTED);
        assertRejected(() -> bundleLimit.vault(dad.id(), dadConversation.id(), "limit/one.html"),
            ErrorCode.ARTIFACT_VAULT_UNSUPPORTED);
        assertRejected(() -> countLimit.vault(dad.id(), dadConversation.id(), "limit/many.html"),
            ErrorCode.ARTIFACT_VAULT_UNSUPPORTED);
        assertRejected(() -> quota.vault(dad.id(), dadConversation.id(), "limit/one.html"),
            ErrorCode.ARTIFACT_VAULT_FULL);
        assertThat(vaultedRowCount(dad)).isZero();
        assertThat(countLimit.vault(dad.id(), dadConversation.id(), "limit/one.html").fileCount()).isEqualTo(2);
    }


    @Test
    @DisplayName("훑은 뒤 보관하는 사이에 원본이 지워지거나 바뀌면 묶음 전체를 되돌린다")
    void rollsBackWhenOriginalChangesWhileKeeping() {
        ArtifactVaultService removing = service(ArtifactVaultProperties.defaults(), (bundle) -> {
            Path folder = Path.of(properties.root()).toAbsolutePath().resolve(String.valueOf(dadConversation.id()));
            uncheckedDelete(folder.resolve("page/img/hero.png"));
        });
        assertRejected(() -> removing.vault(dad.id(), dadConversation.id(), "page/index.html"),
            ErrorCode.ARTIFACT_VAULT_UNSUPPORTED);
        assertThat(vaultedRowCount(dad)).isZero();

        write(dadConversation, "page/img/hero.png", PNG);
        ArtifactVaultService changing = service(ArtifactVaultProperties.defaults(),
            (bundle) -> write(dadConversation, "page/img/big.png", new byte[] {9, 9, 9, 9, 9, 9, 9, 9}));
        assertRejected(() -> changing.vault(dad.id(), dadConversation.id(), "page/index.html"),
            ErrorCode.ARTIFACT_VAULT_UNSUPPORTED);
        assertThat(vaultedRowCount(dad)).isZero();
    }


    @Test
    @DisplayName("링크로 다른 대화의 사진을 가리키면 따라가지 않고 묶음 전체를 거절한다")
    void doesNotFollowLinkIntoAnotherConversation() throws Exception {
        Conversation momConversation = conversation(mom, "엄마 사진");
        write(momConversation, "private.png", PNG);
        Path root = Path.of(properties.root()).toAbsolutePath();
        Path link = root.resolve(String.valueOf(dadConversation.id())).resolve("page/img/borrowed.png");
        Files.createSymbolicLink(link, root.resolve(String.valueOf(momConversation.id())).resolve("private.png"));
        write(dadConversation, "page/borrow.html", "<img src=\"img/borrowed.png\">");

        assertRejected(() -> vault.vault(dad.id(), dadConversation.id(), "page/borrow.html"),
            ErrorCode.ARTIFACT_VAULT_UNSUPPORTED);
        assertThat(vaultedRowCount(dad)).isZero();
    }


    @Test
    @DisplayName("남은 남의 묶음 파일을 읽지도 지우지도 못하고 묶음 안 파일 번호로도 열지 못한다")
    void otherUserCannotReachBundleFiles() throws Exception {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "page/index.html");
        Long partId = jdbc.queryForObject(
            "select id from chat_artifact where vault_bundle_id = ? and path = 'page/img/hero.png'", Long.class,
            item.id());

        assertRejected(() -> vault.readFile(mom.id(), item.id(), "page/img/hero.png"), ErrorCode.ARTIFACT_NOT_FOUND);
        assertRejected(() -> vault.read(dad.id(), partId), ErrorCode.ARTIFACT_NOT_FOUND);
        assertRejected(() -> vault.delete(dad.id(), partId), ErrorCode.ARTIFACT_NOT_FOUND);
        assertThat(http("/api/v1/artifact-vault/" + item.id() + "/files/page/img/hero.png", mom).statusCode())
            .isEqualTo(404);
        assertThat(http("/api/v1/artifact-vault/" + item.id() + "/archive", mom).statusCode()).isEqualTo(404);
        assertThat(http("/api/v1/artifact-vault/" + partId + "/content", dad).statusCode()).isEqualTo(404);
        assertThat(vault.readFile(dad.id(), item.id(), "page/img/hero.png").body()).isEqualTo(PNG);

        // 자식 행의 주인이 뿌리와 다르면 어느 쪽에서도 그 묶음의 파일로 읽히지 않는다.
        jdbc.update("update chat_artifact set vault_user_id = ? where id = ?", mom.id(), partId);
        assertRejected(() -> vault.readFile(dad.id(), item.id(), "page/img/hero.png"), ErrorCode.ARTIFACT_NOT_FOUND);
        assertRejected(() -> vault.readFile(mom.id(), item.id(), "page/img/hero.png"), ErrorCode.ARTIFACT_NOT_FOUND);
    }


    @Test
    @DisplayName("묶음 안 파일을 다른 묶음으로 옮기면 풀리지 않는다")
    void movedPartDoesNotOpen() {
        ArtifactVaultItem first = vault.vault(dad.id(), dadConversation.id(), "page/index.html");
        write(dadConversation, "other/index.html", "<img src=\"x.png\">");
        write(dadConversation, "other/x.png", PNG);
        ArtifactVaultItem second = vault.vault(dad.id(), dadConversation.id(), "other/index.html");
        jdbc.update(
            "update chat_artifact set vault_bundle_id = ? where vault_bundle_id = ? and path = 'page/img/hero.png'",
            second.id(), first.id());

        assertRejected(() -> vault.readFile(dad.id(), second.id(), "page/img/hero.png"),
            ErrorCode.ARTIFACT_VAULT_UNAVAILABLE);
    }


    @Test
    @DisplayName("대화를 지워 정리해도 묶음은 남고 지우면 묶음 안 파일까지 사라진다")
    void bundleSurvivesPurgeAndDeletesWhole() {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "page/index.html");
        conversationWriter.deleteIfActive(dadConversation.id(), dad.id(), NOW.minusSeconds(60));

        purger.purgeDue(NOW);

        assertThat(Path.of(properties.root()).toAbsolutePath().resolve(String.valueOf(dadConversation.id())))
            .doesNotExist();
        assertThat(vault.readFile(dad.id(), item.id(), "page/img/back.webp").body()).isEqualTo(PNG);
        vault.delete(dad.id(), item.id());
        assertThat(vaultedRowCount(dad)).isZero();
        assertThat(rows.sumVaultBytes(dad.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chat_artifact where vault_user_id = ?", Integer.class, dad.id()))
            .as("지운 대화에서는 자동 보관이 돌지 않아 지우기 이력도 남기지 않는다")
            .isZero();
    }


    @Test
    @DisplayName("지운 묶음은 본문과 key 와 경로와 묶음 안 파일 없이 지문만 남고 목록과 읽기와 용량에 나오지 않으며 대화를 정리하면 함께 지워진다")
    void deletedBundleLeavesOnlyFingerprint() throws Exception {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "page/index.html");
        String fingerprint = jdbc.queryForObject(
            "select vault_fingerprint from chat_artifact where id = ?", String.class, item.id());

        vault.delete(dad.id(), item.id());

        Map<String, Object> left = jdbc.queryForMap(
            "select path, byte_size, vault_body, vault_key_id, vault_fingerprint, deleted_at from chat_artifact"
                + " where vault_user_id = ?",
            dad.id());
        assertThat(left.get("path")).isEqualTo("");
        assertThat(((Number)left.get("byte_size")).longValue()).isZero();
        assertThat(left.get("vault_body")).isNull();
        assertThat(left.get("vault_key_id")).isNull();
        assertThat(left.get("vault_fingerprint")).isEqualTo(fingerprint);
        assertThat(left.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject(
            "select count(*) from chat_artifact where vault_bundle_id = ?", Integer.class, item.id()))
                .as("묶음 안 파일 행은 남지 않는다")
                .isZero();
        assertThat(rows.sumVaultBytes(dad.id())).isZero();
        assertThat(vault.list(dad.id())).isEmpty();
        assertRejected(() -> vault.read(dad.id(), item.id()), ErrorCode.ARTIFACT_NOT_FOUND);
        assertRejected(() -> vault.readFile(dad.id(), item.id(), "page/img/back.webp"), ErrorCode.ARTIFACT_NOT_FOUND);
        assertRejected(() -> vault.prepareArchive(dad.id(), item.id()), ErrorCode.ARTIFACT_NOT_FOUND);
        assertRejected(() -> vault.delete(dad.id(), item.id()), ErrorCode.ARTIFACT_NOT_FOUND);
        assertThat(new String(http("/api/v1/artifact-vault", dad).body(), StandardCharsets.UTF_8))
            .as("/files 목록에 나오지 않는다")
            .contains("\"items\":[]");
        assertThat(http("/api/v1/artifact-vault/" + item.id() + "/content", dad).statusCode()).isEqualTo(404);

        conversationWriter.deleteIfActive(dadConversation.id(), dad.id(), NOW.minusSeconds(60));
        purger.purgeDue(NOW);

        assertThat(jdbc.queryForObject("select count(*) from chat_artifact where vault_user_id = ?", Integer.class, dad.id()))
            .as("대화를 정리하면 지우기 이력도 함께 지운다")
            .isZero();
    }


    @Test
    @DisplayName("주인은 HTTP 로 묶음 파일을 미리보기 머리글과 함께 읽고 zip 으로 내려받는다")
    void ownerReadsFilesAndArchiveOverHttp() throws Exception {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "page/index.html");

        HttpResponse<byte[]> page = http("/api/v1/artifact-vault/" + item.id() + "/files/page/index.html", dad);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(new String(page.body(), StandardCharsets.UTF_8)).isEqualTo(PAGE);
        assertThat(page.headers().firstValue("Content-Security-Policy")).get().asString().startsWith("sandbox");
        assertThat(page.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(page.headers().firstValue("Cache-Control")).contains("private, no-store");
        HttpResponse<byte[]> image = http("/api/v1/artifact-vault/" + item.id() + "/files/page/img/a%20b.png", dad);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).isEqualTo(PNG);
        assertThat(image.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(image.headers().firstValue("Cache-Control")).contains("private, no-store");

        HttpResponse<byte[]> archive = http("/api/v1/artifact-vault/" + item.id() + "/archive", dad);
        assertThat(archive.statusCode()).isEqualTo(200);
        assertThat(archive.headers().firstValue("Content-Disposition")).get().asString().contains("index.zip");
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
            }
        }
        assertThat(entries).containsExactly(
            "page/index.html", "page/css/base.css", "page/css/site.css", "page/img/a b.png", "page/img/back.webp",
            "page/img/big.png", "page/img/hero.png");
    }


    @Test
    @DisplayName("사진 하나도 보관하고 사진 형식으로 읽는다")
    void vaultsStandaloneImage() {
        ArtifactVaultItem item = vault.vault(dad.id(), dadConversation.id(), "page/img/unused.png");

        assertThat(item.fileCount()).isEqualTo(1);
        assertThat(vault.read(dad.id(), item.id()).body()).isEqualTo(PNG);
        assertThat(vault.read(dad.id(), item.id()).contentType()).isEqualTo("image/png");
    }


    @Test
    @DisplayName("동시에 도는 보관이 상한에 닿으면 새 요청은 기다리지 않고 거절한다")
    void rejectsWhenTooManyVaultsRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ArtifactVaultService busy = service(ArtifactVaultProperties.defaults(), (bundle) -> {
            entered.countDown();
            await(release);
        });
        write(dadConversation, "other.css", "p{}");
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CompletableFuture<ArtifactVaultItem> first = CompletableFuture.supplyAsync(
            () -> busy.vault(dad.id(), dadConversation.id(), "page/index.html"), threads);
        CompletableFuture<ArtifactVaultItem> second = CompletableFuture.supplyAsync(() -> busy.vault(dad.id(), dadConversation.id(), "other.css"), threads);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertRejected(() -> busy.vault(dad.id(), dadConversation.id(), "page/img/unused.png"),
            ErrorCode.ARTIFACT_VAULT_UNAVAILABLE);
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).fileCount()).isEqualTo(7);
        assertThat(second.get(10, TimeUnit.SECONDS).fileCount()).isEqualTo(1);
        threads.shutdown();
    }


    private ArtifactVaultService service(ArtifactVaultProperties limits) {
        return new ArtifactVaultService(reader, rows, writer, bodies, limits, transactions, Clock.systemUTC());
    }


    /** 훑은 직후에 {@code afterScan} 을 부르는 서비스다. 보관 도중의 원본 변경과 동시 요청을 만든다. */
    private ArtifactVaultService service(ArtifactVaultProperties limits, Consumer<ArtifactBundle> afterScan) {
        ArtifactBundleReader hooked = new ArtifactBundleReader(artifacts) {

            @Override
            public ArtifactBundle scan(Long conversationId, String rootPath, ArtifactVaultProperties scanLimits) {
                ArtifactBundle bundle = super.scan(conversationId, rootPath, scanLimits);
                afterScan.accept(bundle);
                return bundle;
            }
        };
        return new ArtifactVaultService(hooked, rows, writer, bodies, limits, transactions, Clock.systemUTC());
    }


    private static void assertRejected(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }


    private int vaultedRowCount(AppUser user) {
        // 지운 보관본은 본문이 빈 지우기 이력으로 남는다. 본문을 가진 줄만 센다.
        return jdbc.queryForObject(
            "select count(*) from chat_artifact where vault_user_id = ? and vault_body is not null", Integer.class,
            user.id());
    }


    private Conversation conversation(AppUser owner, String title) {
        Conversation conversation = conversations.save(Conversation.startedBy(owner.id(), title, null, NOW));
        folders.add(conversation.id());
        return conversation;
    }


    private void write(Conversation conversation, String path, String content) {
        write(conversation, path, content.getBytes(StandardCharsets.UTF_8));
    }


    private void write(Conversation conversation, String path, byte[] content) {
        store.write(conversation.id(), path, content);
    }


    private static void uncheckedDelete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }


    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    private HttpResponse<byte[]> http(String path, AppUser sender) throws Exception {
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
