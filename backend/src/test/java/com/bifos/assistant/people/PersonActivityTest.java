package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.application.model.PersonAccess;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Date;
import javax.crypto.SecretKey;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 관리자 목록에 사용자 발신 메시지의 마지막 시각만 들어가는지 확인한다. */
@BackendIntegrationTest
class PersonActivityTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "test-secret-test-secret-test-secret-test-secret".getBytes(StandardCharsets.UTF_8));

    @LocalServerPort
    int port;

    @Autowired
    PersonAccessService access;

    @Autowired
    SignInPolicy signIn;

    @Autowired
    TestClock clock;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    EntityManagerFactory entityManagers;

    @Autowired
    HermesProfileProvisioner profiles;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        messages.deleteAll();
        conversations.deleteAll();
        people.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("사용자가 보낸 마지막 메시지만 활동 시각으로 모은다")
    void findsLastUserMessageBySenderRatherThanConversationOwner() {
        AllowedPerson allowed = people.save(AllowedPerson.of("activity@example.com", "사용자", "activity", Instant.EPOCH));
        AppUser user = users.save(AppUser.of("ACTIVITY@example.com", "사용자", 1L, UserRole.MEMBER, Instant.EPOCH));
        AppUser owner =
                users.save(AppUser.of("owner@example.com", "대화 주인", 1L, UserRole.MEMBER, Instant.EPOCH));
        Instant first = Instant.parse("2026-10-08T01:00:00Z");
        Instant last = Instant.parse("2026-10-08T02:00:00Z");
        Conversation firstConversation = conversations.save(Conversation.startedBy(owner.id(), "첫 대화", null, first));
        Conversation secondConversation = conversations.save(Conversation.startedBy(owner.id(), "둘째 대화", null, last));
        messages.save(ChatMessage.fromUser(firstConversation.id(), user.id(), "first", first));
        messages.save(ChatMessage.fromAssistant(firstConversation.id(), "answer", null, last.plusSeconds(60)));
        messages.save(ChatMessage.fromSystem(firstConversation.id(), "notice", last.plusSeconds(120)));
        messages.save(ChatMessage.fromUser(secondConversation.id(), user.id(), "last", last));

        PersonAccess result = access.list().stream()
                .filter(entry -> entry.person().id().equals(allowed.id()))
                .findFirst()
                .orElseThrow();

        assertThat(result.joined()).isTrue();
        assertThat(result.lastConversationAt()).isEqualTo(last);
    }

    @Test
    @DisplayName("대화가 없는 아직 로그인하지 않은 사람은 활동 시각이 비어 있다")
    void returnsNullActivityForPersonWithoutUser() {
        AllowedPerson allowed = people.save(AllowedPerson.of("new@example.com", "새 사용자", "new-user", Instant.EPOCH));

        PersonAccess result = access.list().stream()
                .filter(entry -> entry.person().id().equals(allowed.id()))
                .findFirst()
                .orElseThrow();

        assertThat(result.joined()).isFalse();
        assertThat(result.lastConversationAt()).isNull();
    }

    @Test
    @DisplayName("가입 여부와 상태와 정규화가 달라도 사용자 메시지의 가장 최근 시각을 붙인다")
    void assemblesActivityForJoinedDisabledAndNormalizedUsers() {
        AllowedPerson inactive = people.save(AllowedPerson.of("inactive@example.com", "미가입", "inactive", Instant.EPOCH));
        AllowedPerson joined = people.save(AllowedPerson.of("joined@example.com", "가입", "joined", Instant.EPOCH));
        AllowedPerson noMessage = people.save(AllowedPerson.of("no-message@example.com", "무대화", "no-message", Instant.EPOCH));
        AllowedPerson disabled = people.save(AllowedPerson.of("disabled@example.com", "꺼짐", "disabled", Instant.EPOCH));
        disabled.disable();
        people.save(disabled);
        AllowedPerson duplicate = people.save(AllowedPerson.of("duplicate@example.com", "중복", "duplicate", Instant.EPOCH));
        AppUser joinedUser = users.save(AppUser.of("JOINED@example.com", "가입", 1L, UserRole.MEMBER, Instant.EPOCH));
        users.save(AppUser.of("NO-MESSAGE@example.com", "무대화", 1L, UserRole.MEMBER, Instant.EPOCH));
        AppUser disabledUser = users.save(AppUser.of("disabled@example.com", "꺼짐", 1L, UserRole.MEMBER, Instant.EPOCH));
        AppUser duplicateOld = users.save(AppUser.of("DUPLICATE@example.com", "중복A", 1L, UserRole.MEMBER, Instant.EPOCH));
        AppUser duplicateNew = users.save(AppUser.of("duplicate@example.com", "중복B", 1L, UserRole.MEMBER, Instant.EPOCH));
        Instant old = Instant.parse("2026-10-08T01:00:00Z");
        Instant recent = old.plusSeconds(60);
        Conversation conversation = conversations.save(Conversation.startedBy(joinedUser.id(), "활동", null, old));
        messages.save(ChatMessage.fromUser(conversation.id(), joinedUser.id(), "joined", old));
        messages.save(ChatMessage.fromUser(conversation.id(), disabledUser.id(), "disabled", recent));
        messages.save(ChatMessage.fromUser(conversation.id(), duplicateOld.id(), "old", old));
        messages.save(ChatMessage.fromUser(conversation.id(), duplicateNew.id(), "new", recent));

        List<PersonAccess> rows = access.list();

        assertThat(activity(rows, inactive)).isNull();
        assertThat(activity(rows, joined)).isEqualTo(old);
        PersonAccess noMessageAccess = rows.stream()
                .filter(row -> row.person().id().equals(noMessage.id()))
                .findFirst()
                .orElseThrow();
        assertThat(noMessageAccess.joined()).isTrue();
        assertThat(noMessageAccess.lastConversationAt()).isNull();
        assertThat(activity(rows, disabled)).isEqualTo(recent);
        assertThat(activity(rows, duplicate)).isEqualTo(recent);
    }

    @Test
    @DisplayName("사용자 수가 늘어도 활동 목록은 세 번 질의한다")
    void listsActivityWithThreeQueriesRegardlessOfUserCount() {
        assertThat(listQueriesFor(2)).isEqualTo(3);
        assertThat(listQueriesFor(12)).isEqualTo(3);
    }

    @Test
    @DisplayName("관리자 목록과 추가와 변경 응답은 활동 시각 null을 내보내고 MEMBER는 거절한다")
    void exposesNullableActivityOnlyToAdmin() throws Exception {
        AppUser admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN, Instant.EPOCH));
        AppUser member = users.save(AppUser.of("member@example.com", "사용자", 1L, UserRole.MEMBER, Instant.EPOCH));
        AllowedPerson existing = people.save(AllowedPerson.of("existing@example.com", "기존", "existing", Instant.EPOCH));

        HttpResponse<String> listed = api("GET", "/api/v1/admin/people", token(admin), null);
        assertThat(listed.statusCode()).isEqualTo(200);
        JsonNode row = json.readTree(listed.body()).get(0);
        assertThat(row.path("lastLoginAt").isNull()).isTrue();
        assertThat(row.path("lastConversationAt").isNull()).isTrue();

        HttpResponse<String> updated = api("PATCH", "/api/v1/admin/people/" + existing.id(), token(admin), "{\"enabled\":false}");
        assertThat(updated.statusCode()).isEqualTo(200);
        JsonNode update = json.readTree(updated.body());
        assertThat(update.path("lastLoginAt").isNull()).isTrue();
        assertThat(update.path("lastConversationAt").isNull()).isTrue();

        doNothing().when(profiles).provision(any());
        HttpResponse<String> created = api(
                "POST",
                "/api/v1/admin/people",
                token(admin),
                "{\"email\":\"created@example.com\",\"displayName\":\"새 사용자\",\"hermesProfile\":\"created-user\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        JsonNode added = json.readTree(created.body());
        assertThat(added.path("lastLoginAt").isNull()).isTrue();
        assertThat(added.path("lastConversationAt").isNull()).isTrue();

        assertThat(api("GET", "/api/v1/admin/people", token(member), null).statusCode()).isEqualTo(403);
        assertThat(api("PATCH", "/api/v1/admin/people/" + existing.id(), token(member), "{\"enabled\":true}")
                        .statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("관리자 목록과 변경 응답은 기록된 두 활동 시각을 ISO 형식으로 준다")
    void exposesRecordedActivityToAdminAsIsoInstants() throws Exception {
        AppUser admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN, Instant.EPOCH));
        AllowedPerson person = people.save(AllowedPerson.of("active@example.com", "사용자", "active", Instant.EPOCH));
        AppUser user = users.save(AppUser.of(person.email(), "사용자", 1L, UserRole.MEMBER, Instant.EPOCH));
        Instant login = Instant.parse("2026-10-08T01:00:00Z");
        Instant conversationAt = login.plusSeconds(60);
        clock.set(login);
        assertThat(signIn.recordCompletion(person.email())).isTrue();
        Conversation conversation = conversations.save(Conversation.startedBy(user.id(), "대화", null, conversationAt));
        messages.save(ChatMessage.fromUser(conversation.id(), user.id(), "사용자 메시지", conversationAt));

        HttpResponse<String> listed = api("GET", "/api/v1/admin/people", token(admin), null);
        assertThat(listed.statusCode()).isEqualTo(200);
        JsonNode row = json.readTree(listed.body()).get(0);
        assertThat(row.path("lastLoginAt").asString()).isEqualTo(login.toString());
        assertThat(row.path("lastConversationAt").asString()).isEqualTo(conversationAt.toString());

        HttpResponse<String> updated = api("PATCH", "/api/v1/admin/people/" + person.id(), token(admin), "{\"enabled\":false}");
        assertThat(updated.statusCode()).isEqualTo(200);
        JsonNode update = json.readTree(updated.body());
        assertThat(update.path("lastLoginAt").asString()).isEqualTo(login.toString());
        assertThat(update.path("lastConversationAt").asString()).isEqualTo(conversationAt.toString());
    }

    @Test
    @DisplayName("관리자 변경에 오래된 허용 목록 행을 저장해도 로그인 시각은 유지한다")
    void preservesLoginTimeWhenAdminSavesStalePerson() {
        AllowedPerson person = people.save(AllowedPerson.of("stale@example.com", "사용자", "stale", Instant.EPOCH));
        AllowedPerson staleDisable = people.findById(person.id()).orElseThrow();
        Instant firstLogin = Instant.parse("2026-10-08T01:00:00Z");
        clock.set(firstLogin);

        assertThat(signIn.recordCompletion("stale@example.com")).isTrue();
        staleDisable.disable();
        people.save(staleDisable);
        assertThat(people.findById(person.id()).orElseThrow().lastLoginAt()).isEqualTo(firstLogin);

        AllowedPerson staleEnable = people.findById(person.id()).orElseThrow();
        AllowedPerson current = people.findById(person.id()).orElseThrow();
        current.enable();
        people.save(current);
        Instant secondLogin = firstLogin.plusSeconds(60);
        clock.set(secondLogin);
        assertThat(signIn.recordCompletion("stale@example.com")).isTrue();

        staleEnable.enable();
        people.save(staleEnable);
        assertThat(people.findById(person.id()).orElseThrow().lastLoginAt()).isEqualTo(secondLogin);
    }

    private Instant activity(List<PersonAccess> rows, AllowedPerson person) {
        return rows.stream()
                .filter(row -> row.person().id().equals(person.id()))
                .findFirst()
                .orElseThrow()
                .lastConversationAt();
    }

    private long listQueriesFor(int count) {
        messages.deleteAll();
        conversations.deleteAll();
        people.deleteAll();
        users.deleteAll();
        Instant now = Instant.parse("2026-10-08T03:00:00Z");
        for (int index = 0; index < count; index++) {
            String email = "list-" + index + "@example.com";
            people.save(AllowedPerson.of(email, "사용자", "list-" + index, now));
            AppUser user = users.save(AppUser.of(email, "사용자", 1L, UserRole.MEMBER, now));
            Conversation conversation = conversations.save(Conversation.startedBy(user.id(), "목록", null, now));
            messages.save(ChatMessage.fromUser(conversation.id(), user.id(), "message", now));
        }
        Statistics statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        assertThat(access.list()).hasSize(count);

        return statistics.getPrepareStatementCount();
    }

    private HttpResponse<String> api(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        if (body == null) {
            request.GET();
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(AppUser user) {
        return Jwts.builder()
                .subject(user.email())
                .claim("name", user.displayName())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(KEY)
                .compact();
    }
}
