package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.bifos.assistant.notification.application.NotificationEventHub;
import com.bifos.assistant.notification.application.NotificationProperties;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.notification.presentation.NotificationController;
import com.bifos.assistant.notification.presentation.NotificationEventStreams;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림 경로의 응답 모양과 사용자 단위 SSE 를 본다(ADR-070).
 *
 * <p>스트림은 끝나지 않으므로 끝까지 기다리지 않고, 사건을 낸 뒤 그때까지 쓰인 응답을 읽는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationControllerTest {

    private static final long USER = 970_201L;
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    NotificationService service;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    NotificationEventHub hub;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<MvcResult> streams = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        notifications.deleteAll();
        when(currentUser.require())
                .thenReturn(new CurrentUser(USER, "notice@example.com", "알림 받는 사람", 1L, UserRole.MEMBER));
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(
                        currentUser,
                        service,
                        hub,
                        new NotificationEventStreams(
                                new NotificationProperties(Duration.ofDays(90), "-", Duration.ofSeconds(20)))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        // 연 스트림을 끝내 hub 의 구독과 주석 줄 스레드가 다음 검사로 남지 않게 한다.
        streams.forEach(stream -> stream.getRequest().getAsyncContext().complete());
        streams.clear();
        notifications.deleteAll();
    }

    @Test
    @DisplayName("목록은 알림 칸과 다음 자리와 읽지 않은 수를 돌려준다")
    void listReturnsItemsCursorAndUnreadCount() throws Exception {
        UUID conversation = UUID.randomUUID();
        Notification stored = notifications.save(Notification.of(
                USER,
                NotificationKind.APPROVAL_REQUESTED,
                "승인을 기다리는 요청이 있어요",
                "「메모 쓰기」",
                new NotificationTarget(NotificationTargetType.CONVERSATION, conversation),
                CREATED_AT));

        JsonNode page =
                body(mvc.perform(get("/api/v1/notifications")).andReturn().getResponse(), 200);

        assertThat(page.path("unreadCount").asLong()).isEqualTo(1);
        assertThat(page.path("nextCursor").isNull())
                .as("마지막 쪽의 nextCursor: %s", page)
                .isTrue();
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("id").asString()).isEqualTo(stored.publicId().toString());
        assertThat(item.path("kind").asString()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(item.path("title").asString()).isEqualTo("승인을 기다리는 요청이 있어요");
        assertThat(item.path("body").asString()).isEqualTo("「메모 쓰기」");
        assertThat(item.path("targetType").asString()).isEqualTo("CONVERSATION");
        assertThat(item.path("targetId").asString()).isEqualTo(conversation.toString());
        assertThat(Instant.parse(item.path("createdAt").asString())).isEqualTo(CREATED_AT);
        assertThat(item.path("readAt").isNull()).as("읽지 않은 줄의 readAt: %s", item).isTrue();
    }

    @Test
    @DisplayName("읽음은 읽은 시각이 채워진 알림을, 모두 읽음은 읽지 않은 수 0 을 돌려준다")
    void readAndReadAllReturnTheirShapes() throws Exception {
        Notification first = stored();
        stored();

        JsonNode read = body(
                mvc.perform(post("/api/v1/notifications/{id}/read", first.publicId()))
                        .andReturn()
                        .getResponse(),
                200);
        JsonNode all = body(
                mvc.perform(post("/api/v1/notifications/read-all")).andReturn().getResponse(), 200);

        assertThat(read.path("id").asString()).isEqualTo(first.publicId().toString());
        assertThat(read.path("readAt").isNull())
                .as("읽음으로 표시한 줄의 readAt: %s", read)
                .isFalse();
        assertThat(all.properties()).hasSize(1);
        assertThat(all.path("unreadCount").asLong()).isZero();
    }

    @Test
    @DisplayName("형식이 틀린 notificationId 는 400 VALIDATION_FAILED 다")
    void malformedNotificationIdIsValidationFailed() throws Exception {
        JsonNode error = body(
                mvc.perform(post("/api/v1/notifications/{id}/read", "not-a-uuid"))
                        .andReturn()
                        .getResponse(),
                400);

        assertThat(error.path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("없는 알림의 읽음은 404 NOTIFICATION_NOT_FOUND 다")
    void unknownNotificationIsNotFound() throws Exception {
        JsonNode error = body(
                mvc.perform(post("/api/v1/notifications/{id}/read", UUID.randomUUID()))
                        .andReturn()
                        .getResponse(),
                404);

        assertThat(error.path("code").asString()).isEqualTo("NOTIFICATION_NOT_FOUND");
    }

    @Test
    @DisplayName("사건 경로는 text/event-stream 으로 열리고 사건 없이도 주석 connected 를 먼저 보낸다")
    void eventStreamOpensWithConnectedComment() throws Exception {
        MvcResult subscribed = subscribe();

        MockHttpServletResponse response = subscribed.getResponse();
        assertThat(response.getContentType()).startsWith("text/event-stream");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .as("주석 줄 간격(20초)을 기다리지 않고 받은 응답")
                .startsWith(":connected\n");
        assertThat(received(subscribed)).isEmpty();
    }

    @Test
    @DisplayName("created 사건에는 notificationId 가 있고 read 사건에는 그 키가 없다")
    void createdEventCarriesIdButReadEventDoesNot() throws Exception {
        MvcResult subscribed = subscribe();

        Notification created = new TransactionTemplate(transactionManager)
                .execute(status -> service.notify(USER, NotificationKind.APPROVAL_EXPIRED, "승인 요청이 만료됐어요", "", null));
        service.markAllRead(currentUser.require());

        List<JsonNode> events = received(subscribed);
        assertThat(events).as("받은 사건: %s", events).hasSize(2);
        JsonNode createdEvent = events.get(0);
        assertThat(createdEvent.path("type").asString()).isEqualTo("created");
        assertThat(createdEvent.path("notificationId").asString())
                .isEqualTo(created.publicId().toString());
        assertThat(createdEvent.path("unreadCount").asLong()).isEqualTo(1);
        JsonNode readEvent = events.get(1);
        assertThat(readEvent.path("type").asString()).isEqualTo("read");
        assertThat(readEvent.has("notificationId"))
                .as("read 사건에 notificationId 키가 실렸다: %s", readEvent)
                .isFalse();
        assertThat(readEvent.path("unreadCount").asLong()).isZero();
    }

    private Notification stored() {
        return notifications.save(
                Notification.of(USER, NotificationKind.APPROVAL_REQUESTED, "제목", "본문", null, CREATED_AT));
    }

    private MvcResult subscribe() throws Exception {
        MvcResult subscribed = mvc.perform(get("/api/v1/notifications/events"))
                .andExpect(request().asyncStarted())
                .andReturn();
        streams.add(subscribed);
        return subscribed;
    }

    private JsonNode body(MockHttpServletResponse response, int status) throws Exception {
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("응답: %s", text).isEqualTo(status);
        return json.readTree(text);
    }

    /** 지금까지 쓰인 응답에서 {@code data:} 줄마다 사건 하나로 읽는다. */
    private List<JsonNode> received(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> events = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("data:")) {
                events.add(json.readTree(line.substring("data:".length())));
            }
        }
        return events;
    }
}
