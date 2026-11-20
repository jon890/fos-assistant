package com.bifos.assistant.chat.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactKept;
import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 「내 파일」 목록 API 를 확인한다(스펙 workspace REQ10~REQ14, REQ17).
 *
 * <p>쪽 나누기, 종류 거르기, 제목·설명·대화 제목 찾기, 대화로 거르기, 지운 대화, 용량 칸, 글 형식의 미리보기와 내려받기 이름을 본다.
 */
@BackendIntegrationTest
class ArtifactVaultListTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

    @LocalServerPort
    int port;

    @Autowired
    ArtifactVaultService vault;

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

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> made = new ArrayList<>();
    private AppUser mom;
    private AppUser dad;
    private Conversation budget;
    private Conversation trip;


    @BeforeEach
    void setUp() {
        mom = member("list-mom-" + UUID.randomUUID());
        dad = member("list-dad-" + UUID.randomUUID());
        budget = conversation(dad, "가계부 정리");
        trip = conversation(dad, "제주 여행 계획");
    }


    @AfterEach
    void tearDown() {
        rows.deleteAll(rows.findAll().stream().filter(row -> made.contains(row.conversationId())).toList());
        made.forEach(store::deleteFolder);
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("이어서 읽으면 최신순으로 빠지거나 겹치는 줄 없이 모두 읽히고 마지막 쪽에는 다음 쪽이 없다")
    void pagesWithoutGapsOrOverlaps() throws Exception {
        List<Long> kept = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            kept.add(keep(budget, "note-" + index + ".txt", text("메모 " + index), null, null).item().id());
        }

        JsonNode first = list(dad, "limit=2");
        JsonNode second = list(dad, "limit=2&before=" + first.get("nextCursor").asLong());
        JsonNode third = list(dad, "limit=2&before=" + second.get("nextCursor").asLong());

        List<Long> read = new ArrayList<>();
        read.addAll(ids(first));
        read.addAll(ids(second));
        read.addAll(ids(third));
        assertThat(read).containsExactlyElementsOf(kept.reversed());
        assertThat(third.get("nextCursor").isNull()).isTrue();
    }


    @Test
    @DisplayName("표를 고르면 표만 남고 설명이나 대화 제목에만 든 낱말로도 찾으며 종류와 함께 적용한다")
    void filtersByKindAndText() throws Exception {
        Long described = keep(budget, "a.csv", text("가,나"), "지출 표", "제주 여행 경비를 모았다").item().id();
        Long inTrip = keep(trip, "b.xlsx", zip(64), "숙소 목록", null).item().id();
        Long image = keep(trip, "c.png", png(), "바다 사진", null).item().id();
        keep(budget, "d.txt", text("글"), "장보기", null);

        assertThat(ids(list(dad, "kind=TABLE"))).containsExactlyInAnyOrder(described, inTrip);
        assertThat(ids(list(dad, "q=" + encode("여행")))).containsExactlyInAnyOrder(described, inTrip, image);
        assertThat(ids(list(dad, "q=" + encode("여행") + "&kind=TABLE"))).containsExactlyInAnyOrder(described, inTrip);
        assertThat(ids(list(dad, "q=" + encode("B.XLSX")))).as("파일 이름은 대소문자를 가리지 않는다").containsExactly(inTrip);
        assertThat(ids(list(mom, "q=" + encode("여행")))).as("남의 파일은 찾지 않는다").isEmpty();
    }


    @Test
    @DisplayName("대화로 거르면 그 대화의 파일만 남고 지운 대화의 파일은 대화 칸이 비며 그 제목으로 찾지 못한다")
    void filtersByConversationAndShowsDeleted() throws Exception {
        Long inBudget = keep(budget, "a.txt", text("가"), null, null).item().id();
        Long inTrip = keep(trip, "b.txt", text("나"), null, null).item().id();

        JsonNode filtered = list(dad, "conversation=" + budget.publicId());
        assertThat(ids(filtered)).containsExactly(inBudget);
        assertThat(filtered.get("items").get(0).get("conversationId").asString()).isEqualTo(budget.publicId().toString());
        assertThat(filtered.get("items").get(0).get("conversationTitle").asString()).isEqualTo("가계부 정리");
        assertThat(get("/api/v1/artifact-vault?conversation=" + budget.publicId(), mom).statusCode())
            .as("남의 대화로는 거르지 못한다")
            .isEqualTo(404);

        jdbc.update("update conversation set deleted_at = ? where id = ?", Timestamp.from(NOW), trip.id());

        JsonNode item = list(dad, "").get("items").get(0);
        assertThat(item.get("id").asLong()).isEqualTo(inTrip);
        assertThat(item.get("conversationId").isNull()).isTrue();
        assertThat(item.get("conversationTitle").isNull()).isTrue();
        assertThat(ids(list(dad, "q=" + encode("제주")))).isEmpty();
    }


    @Test
    @DisplayName("목록에 쓴 용량과 전체 용량이 있고 넣고 지우면 쓴 용량이 따라 바뀐다")
    void reportsUsage() throws Exception {
        long before = list(dad, "").get("usedBytes").asLong();
        ArtifactKept kept = keep(budget, "a.txt", text("12345"), null, null);

        JsonNode after = list(dad, "");
        assertThat(after.get("usedBytes").asLong()).isEqualTo(before + kept.item().byteSize());
        assertThat(after.get("quotaBytes").asLong()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(after.get("full").asBoolean()).isFalse();

        vault.delete(dad.id(), kept.item().id());
        assertThat(list(dad, "").get("usedBytes").asLong()).isEqualTo(before);
    }


    @Test
    @DisplayName("보관본 하나를 번호로 읽고 남의 번호는 없는 번호와 같다")
    void readsOne() throws Exception {
        Long id = keep(budget, "a.md", text("# 제목"), "회의록", "11월 회의").item().id();

        HttpResponse<byte[]> response = get("/api/v1/artifact-vault/" + id, dad);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode item = json.readTree(response.body());
        assertThat(item.get("title").asString()).isEqualTo("회의록");
        assertThat(item.get("conversationTitle").asString()).isEqualTo("가계부 정리");
        assertThat(get("/api/v1/artifact-vault/" + id, mom).statusCode()).isEqualTo(404);
    }


    @Test
    @DisplayName("CSV 는 화면에서 글로 열리고 내려받는 이름은 제목에 원래 확장자를 붙인 것이며 바이트가 같다")
    void servesTextInlineAndNamesDownloadByTitle() throws Exception {
        byte[] body = text("이름,값\n쌀,1");
        Long id = keep(budget, "out/data.csv", body, "11월/가계부", null).item().id();

        HttpResponse<byte[]> inline = get("/api/v1/artifact-vault/" + id + "/content", dad);
        assertThat(inline.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
        assertThat(inline.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("inline");
        assertThat(inline.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");

        HttpResponse<byte[]> download = get("/api/v1/artifact-vault/" + id + "/content?download=1", dad);
        assertThat(download.body()).isEqualTo(body);
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
            .startsWith("attachment")
            .contains(encode("11월_가계부.csv").replace("+", "%20"));
    }


    @Test
    @DisplayName("모르는 종류와 범위를 넘는 줄 수는 입력 오류다")
    void rejectsBadArguments() throws Exception {
        assertThat(get("/api/v1/artifact-vault?kind=VIDEO", dad).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/artifact-vault?limit=0", dad).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/artifact-vault?limit=101", dad).statusCode()).isEqualTo(400);
    }


    private ArtifactKept keep(Conversation conversation, String path, byte[] bytes, String title, String description) {
        return vault.keep(
            conversation.userId(), conversation.id(), VaultOrigin.PUT_WORKSPACE, path, bytes, title, description, null);
    }


    private JsonNode list(AppUser sender, String query) throws Exception {
        HttpResponse<byte[]> response = get("/api/v1/artifact-vault?" + query, sender);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }


    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }


    private Conversation conversation(AppUser owner, String title) {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), title, null, NOW));
        made.add(saved.id());
        return saved;
    }


    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }


    private static byte[] zip(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte)'x');
        bytes[0] = 'P';
        bytes[1] = 'K';
        bytes[2] = 3;
        bytes[3] = 4;
        return bytes;
    }


    private static byte[] png() {
        return new byte[] {(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }


    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
