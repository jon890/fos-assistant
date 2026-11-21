package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.application.PushDeviceService;
import com.bifos.assistant.notification.application.dto.PushRegistration;
import com.bifos.assistant.notification.domain.PushDevice;
import com.bifos.assistant.notification.domain.PushDevices;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.infra.P256;
import com.bifos.assistant.notification.infra.PushDeviceRepository;
import com.bifos.assistant.notification.infra.PushDevicesCodec;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.RecordingWebPushClient;
import com.bifos.assistant.testsupport.RecordingWebPushClient.Sent;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림이 커밋되면 그 사용자의 기기로 웹 푸시를 보내는지 본다(ADR-20261120 / web-push).
 *
 * <p>보내기는 {@link RecordingWebPushClient} 가 네트워크 없이 받는다. 기기 키는 검사 안에서 만든 P-256 키 쌍이라 받은 본문을 풀어 볼 수
 * 있다. 계약은 {@code docs/features/attention/design.md} 의 「보내기」 다.
 */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PushDispatcherTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    NotificationService notifications;

    @Autowired
    PushDeviceService devices;

    @Autowired
    PushDeviceRepository repository;

    @Autowired
    RecordingWebPushClient client;

    @Autowired
    TrackingBackgroundTasks background;

    @Autowired
    VapidKeys keys;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdPeople = new ArrayList<>();
    private CurrentUser alice;
    private CurrentUser bob;


    @BeforeEach
    void setUp() {
        alice = user("alice");
        bob = user("bob");
    }


    @AfterEach
    void tearDown() {
        users.deleteAllById(createdUsers);
        people.deleteAllById(createdPeople);
    }


    @Test
    @DisplayName("알림이 커밋되면 그 사용자의 기기로만 보내고, 풀면 제목과 본문과 여는 주소가 나온다")
    void sendsToOwnDevicesOnly() throws Exception {
        Browser aliceBrowser = Browser.create("https://fcm.googleapis.com/fcm/send/alice-1");
        Browser bobBrowser = Browser.create("https://fcm.googleapis.com/fcm/send/bob-1");
        devices.register(alice, aliceBrowser.registration(keys.publicKeyBase64Url()));
        devices.register(bob, bobBrowser.registration(keys.publicKeyBase64Url()));

        Notification saved = notify(alice, "「아침 뉴스」 실행을 마쳤어요", "본문 한 줄");
        background.awaitIdle(WAIT);

        assertThat(client.sent()).singleElement().satisfies(sent -> {
            assertThat(sent.endpoint()).isEqualTo(aliceBrowser.endpoint());
            assertThat(sent.authorization()).startsWith("vapid t=");
        });
        JsonNode payload = json.readTree(aliceBrowser.decrypt(client.sent().getFirst()));
        assertThat(payload.path("title").asString()).isEqualTo("「아침 뉴스」 실행을 마쳤어요");
        assertThat(payload.path("body").asString()).isEqualTo("본문 한 줄");
        assertThat(payload.path("url").asString()).isEqualTo("/n/" + saved.publicId());
        assertThat(payload.path("tag").asString()).isEqualTo(saved.publicId().toString());
    }


    @Test
    @DisplayName("410 이면 그 기기를 지우고, 로그에 주소와 키를 남기지 않는다")
    void goneRemovesDevice(CapturedOutput output) throws Exception {
        Browser browser = Browser.create("https://fcm.googleapis.com/fcm/send/gone-1");
        devices.register(alice, browser.registration(keys.publicKeyBase64Url()));
        client.respond(410);

        notify(alice, "제목", "본문");
        background.awaitIdle(WAIT);

        assertThat(devices.list(alice)).isEmpty();
        assertThat(output.getAll()).doesNotContain(browser.endpoint()).doesNotContain(browser.p256dh()).doesNotContain(browser.auth());
    }


    @Test
    @DisplayName("500 이면 다시 시도 횟수만큼 더 보내고 버리며 기기는 남는다")
    void transientFailureRetriesThenDrops(CapturedOutput output) throws Exception {
        Browser browser = Browser.create("https://fcm.googleapis.com/fcm/send/busy-1");
        devices.register(alice, browser.registration(keys.publicKeyBase64Url()));
        client.respond(500, 500, 500, 500);

        notify(alice, "제목", "본문");
        background.awaitIdle(WAIT);

        assertThat(client.sent()).hasSize(3);
        assertThat(devices.list(alice)).hasSize(1);
        assertThat(output.getAll()).doesNotContain(browser.endpoint()).doesNotContain(browser.p256dh()).doesNotContain(browser.auth());
    }


    @Test
    @DisplayName("429 의 Retry-After 는 상한 안에서 따르고, 500 의 Retry-After 는 따르지 않는다")
    void retryAfterOnlyFor429And503() throws Exception {
        devices.register(alice, Browser.create("https://fcm.googleapis.com/fcm/send/retry-after-1").registration(keys.publicKeyBase64Url()));
        client.respond(429, Duration.ofMinutes(10));
        client.respond(500, Duration.ofMinutes(10));

        long started = System.nanoTime();
        notify(alice, "제목", "본문");
        background.awaitIdle(WAIT);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(client.sent()).hasSize(3);
        assertThat(took).as("상한(검사 설정 50ms)과 다시 시도 간격만 기다린다").isLessThan(Duration.ofSeconds(5));
    }


    @Test
    @DisplayName("같은 기기가 다른 사용자에게도 있거나 허용 밖 주소가 저장돼 있으면 보내지 않는다")
    void skipsOverlappingOrDisallowedDevices() throws Exception {
        Browser shared = Browser.create("https://fcm.googleapis.com/fcm/send/shared-1");
        PushDevice sharedDevice = shared.device();
        PushDevice internal = Browser.create("https://internal.example/x").device();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.storeDevices(alice.id(), PushDevicesCodec.encode(new PushDevices(List.of(sharedDevice, internal))));
            repository.storeDevices(bob.id(), PushDevicesCodec.encode(new PushDevices(List.of(sharedDevice))));
        });

        notify(alice, "제목", "본문");
        background.awaitIdle(WAIT);

        assertThat(client.sent()).isEmpty();
    }


    @Test
    @DisplayName("알림 트랜잭션이 되돌아가면 보내지 않는다")
    void rollbackSendsNothing() throws Exception {
        devices.register(alice, Browser.create("https://fcm.googleapis.com/fcm/send/rollback-1").registration(keys.publicKeyBase64Url()));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notifications.notify(alice.id(), NotificationKind.TASK_SUCCEEDED, "제목", "본문", null);
            status.setRollbackOnly();
        });
        background.awaitIdle(WAIT);

        assertThat(client.sent()).isEmpty();
    }


    @Test
    @DisplayName("푸시 서비스가 늦어도 알림을 만든 트랜잭션은 바로 커밋되고 돌아온다")
    void slowPushServiceDoesNotBlockNotification() throws Exception {
        devices.register(alice, Browser.create("https://fcm.googleapis.com/fcm/send/slow-1").registration(keys.publicKeyBase64Url()));
        CountDownLatch open = new CountDownLatch(1);
        client.holdUntil(open);
        try {
            long started = System.nanoTime();
            notify(alice, "제목", "본문");
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).isLessThan(Duration.ofSeconds(5));
            assertThat(notifications.unreadCount(alice.id())).isEqualTo(1);
        } finally {
            open.countDown();
        }
        background.awaitIdle(WAIT);
        assertThat(client.sent()).hasSize(1);
    }


    @Test
    @DisplayName("서버에 키가 없으면 아무것도 보내지 않는다")
    void disabledSendsNothing() throws Exception {
        devices.register(alice, Browser.create("https://fcm.googleapis.com/fcm/send/off-1").registration(keys.publicKeyBase64Url()));
        when(keys.enabled()).thenReturn(false);

        notify(alice, "제목", "본문");
        background.awaitIdle(WAIT);

        assertThat(client.sent()).isEmpty();
    }


    @Test
    @DisplayName("기기 해제 알림은 기기가 있어도 푸시로 보내지 않는다")
    void resetNoticeIsNotPushed() throws Exception {
        devices.register(alice, Browser.create("https://fcm.googleapis.com/fcm/send/reset-1").registration(keys.publicKeyBase64Url()));

        new TransactionTemplate(transactionManager).execute(status -> notifications.notify(
            alice.id(), NotificationKind.PUSH_DEVICES_RESET, "알림 받기가 해제됐어요", "본문", null));
        background.awaitIdle(WAIT);

        assertThat(client.sent()).isEmpty();
    }


    private Notification notify(CurrentUser user, String title, String body) {
        return new TransactionTemplate(transactionManager).execute(status -> notifications.notify(user.id(), NotificationKind.TASK_SUCCEEDED, title, body, null));
    }


    private CurrentUser user(String name) {
        String email = name + "-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, name, 1L, UserRole.MEMBER));
        createdPeople.add(people.save(AllowedPerson.of(email, name, "push-" + UUID.randomUUID())).id());
        createdUsers.add(saved.id());
        return new CurrentUser(saved.id(), email, name, 1L, UserRole.MEMBER);
    }


    /** 검사 안의 브라우저 하나다. 구독 키 쌍과 인증 값을 갖고 받은 본문을 푼다. */
    private record Browser(String endpoint, KeyPair keys, byte[] authSecret) {

        static Browser create(String endpoint) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256.params());
            byte[] auth = new byte[16];
            new SecureRandom().nextBytes(auth);
            return new Browser(endpoint, generator.generateKeyPair(), auth);
        }


        String p256dh() {
            return B64.encodeToString(P256.encode((ECPublicKey)keys.getPublic()));
        }


        String auth() {
            return B64.encodeToString(authSecret);
        }


        PushRegistration registration(String vapidPublicKey) {
            return new PushRegistration(endpoint, p256dh(), auth(), "검사 브라우저", "Asia/Seoul", vapidPublicKey);
        }


        PushDevice device() {
            return new PushDevice(UUID.randomUUID(), endpoint, Sha256.hex(endpoint), p256dh(), auth(), "검사", "UTC", Instant.now());
        }


        byte[] decrypt(Sent sent) throws Exception {
            return WebPushTestDecryptor.decrypt(
                sent.body(), keys.getPrivate(), P256.encode((ECPublicKey)keys.getPublic()), authSecret);
        }
    }
}
