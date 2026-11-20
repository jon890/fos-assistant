package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.bifos.assistant.notification.application.PushDeviceService;
import com.bifos.assistant.notification.application.PushRegistrationLock;
import com.bifos.assistant.notification.application.dto.PushRegistration;
import com.bifos.assistant.notification.domain.PushDevice;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 웹 푸시 기기의 등록, 옮기기, 해제, 접근 해제를 실제 DB 로 본다(ADR-20261120 / web-push).
 *
 * <p>계약은 {@code docs/features/attention/design.md} 의 「저장」 과 「등록의 순서」 다.
 */
@BackendIntegrationTest
class PushDeviceServiceTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/device-a";
    private static final TestSubscriptionKeys KEYS = TestSubscriptionKeys.create();

    @Autowired
    PushDeviceService service;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    PersonAccessService personAccess;

    @Autowired
    VapidKeys keys;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PushRegistrationLock registrationLock;

    @Autowired
    TestClock contextClock;

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
    @DisplayName("등록한 기기가 목록에 보이고 같은 주소로 다시 등록해도 한 줄이다")
    void registerThenListOnce() {
        PushDevice first = service.register(alice, registration(ENDPOINT, "iPhone 앱"));
        PushDevice again = service.register(alice, registration(ENDPOINT, "iPhone 앱"));

        List<PushDevice> devices = service.list(alice);

        assertThat(devices).singleElement().satisfies(device -> {
            assertThat(device.id()).isEqualTo(first.id()).isEqualTo(again.id());
            assertThat(device.endpointHash()).isEqualTo(Sha256.hex(ENDPOINT));
            assertThat(device.name()).isEqualTo("iPhone 앱");
        });
    }


    @Test
    @DisplayName("다른 사용자가 같은 주소를 등록하면 앞 사용자의 목록에서 빠진다")
    void registerMovesToLastUser() {
        service.register(alice, registration(ENDPOINT, "공용 PC"));

        service.register(bob, registration(ENDPOINT, "공용 PC"));

        assertThat(service.list(alice)).isEmpty();
        assertThat(service.list(bob)).hasSize(1);
    }


    @Test
    @DisplayName("등록은 서버 안 잠금을 잡아야 돌고, 잠금이 풀린 뒤에 끝난다")
    void registrationWaitsForLock() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> registrationLock.run(() -> {
            held.countDown();
            awaitQuietly(release);
            return null;
        }));
        held.await(5, TimeUnit.SECONDS);

        CompletableFuture<PushDevice> registering = CompletableFuture.supplyAsync(() -> service.register(alice, registration(ENDPOINT, "PC")));
        try {
            Thread.sleep(300);
            assertThat(registering).as("잠금을 쥔 동안 등록은 끝나지 않는다").isNotDone();
        } finally {
            release.countDown();
        }
        holder.get(5, TimeUnit.SECONDS);
        assertThat(registering.get(10, TimeUnit.SECONDS).endpointHash()).isEqualTo(Sha256.hex(ENDPOINT));
    }


    @Test
    @DisplayName("410 을 받기 전에 같은 주소로 다시 등록한 기기는 정리가 지우지 않는다")
    void removeIfUnchangedKeepsReRegistered() {
        contextClock.set(Instant.parse("2026-11-20T00:00:00Z"));
        PushDevice sent = service.register(alice, registration(ENDPOINT, "PC"));
        contextClock.set(Instant.parse("2026-11-20T00:01:00Z"));
        service.register(alice, registration(ENDPOINT, "PC"));

        service.removeIfUnchanged(alice.id(), sent);
        assertThat(service.list(alice)).hasSize(1);

        service.removeIfUnchanged(alice.id(), service.list(alice).getFirst());
        assertThat(service.list(alice)).isEmpty();
    }


    @Test
    @DisplayName("두 사용자가 같은 주소를 동시에 등록해도 끝나면 한 사람에게만 있다")
    void concurrentRegistrationLeavesOneOwner() throws Exception {
        for (int round = 0; round < 5; round++) {
            String endpoint = ENDPOINT + "-race-" + round;
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<?> first = CompletableFuture.runAsync(() -> awaitThenRegister(start, alice, endpoint));
            CompletableFuture<?> second = CompletableFuture.runAsync(() -> awaitThenRegister(start, bob, endpoint));
            start.countDown();
            CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

            String hash = Sha256.hex(endpoint);
            long owners = List.of(alice, bob).stream()
                .filter(user -> service.list(user).stream().anyMatch(device -> device.endpointHash().equals(hash)))
                .count();
            assertThat(owners).as("round %d", round).isEqualTo(1);
        }
    }


    @Test
    @DisplayName("이 브라우저의 해제는 내 기기만 빼고 없는 주소면 아무것도 하지 않는다")
    void unregisterCurrent() {
        service.register(alice, registration(ENDPOINT, "PC"));
        service.register(alice, registration(ENDPOINT + "-2", "휴대폰"));

        service.unregisterCurrent(alice, ENDPOINT);
        service.unregisterCurrent(alice, "https://fcm.googleapis.com/fcm/send/none");

        assertThat(service.list(alice)).extracting(PushDevice::name).containsExactly("휴대폰");
    }


    @Test
    @DisplayName("관리자가 접근을 끄면 그 사용자의 기기가 모두 지워진다")
    void accessRevokedClearsDevices() {
        AllowedPerson person = people.save(AllowedPerson.of(alice.email(), "앨리스", "push-" + UUID.randomUUID()));
        createdPeople.add(person.id());
        service.register(alice, registration(ENDPOINT, "PC"));

        personAccess.setEnabled(person.id(), false);

        assertThat(service.list(alice)).isEmpty();
    }


    @Test
    @DisplayName("서버에 키가 없으면 등록을 받지 않는다")
    void registerRequiresKeys() {
        when(keys.enabled()).thenReturn(false);

        assertThatThrownBy(() -> service.register(alice, registration(ENDPOINT, "PC")))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PUSH_NOT_CONFIGURED));
    }


    @Test
    @DisplayName("허용하지 않은 주소와 모르는 시간대는 받지 않는다")
    void registerValidates() {
        assertThatThrownBy(() -> service.register(alice, registration("https://internal.example/x", "PC")))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.register(alice,
            new PushRegistration(ENDPOINT, KEYS.p256dh(), KEYS.auth(), "PC", "Mars/Olympus")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.register(alice,
            new PushRegistration(ENDPOINT, "BPublicKeyOfBrowser", KEYS.auth(), "PC", "Asia/Seoul")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.register(alice,
            new PushRegistration(ENDPOINT, KEYS.p256dh(), "c2hvcnQ", "PC", "Asia/Seoul")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(service.list(alice)).isEmpty();
    }


    @Test
    @DisplayName("기기를 바꿔도 사용자의 마지막 변경 시각은 바뀌지 않는다")
    void registerKeepsUpdatedAt() {
        Object before = updatedAt(alice.id());

        service.register(alice, registration(ENDPOINT, "PC"));

        assertThat(updatedAt(alice.id())).isEqualTo(before);
    }


    private void awaitThenRegister(CountDownLatch start, CurrentUser user, String endpoint) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        service.register(user, registration(endpoint, "PC"));
    }


    private Object updatedAt(Long userId) {
        return jdbc.queryForList("SELECT updated_at FROM app_user WHERE id = ?", userId).getFirst().get("UPDATED_AT");
    }


    private CurrentUser user(String name) {
        String email = name + "-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, name, 1L, UserRole.MEMBER));
        createdUsers.add(saved.id());
        return new CurrentUser(saved.id(), email, name, 1L, UserRole.MEMBER);
    }


    private static PushRegistration registration(String endpoint, String name) {
        return new PushRegistration(endpoint, KEYS.p256dh(), KEYS.auth(), name, "Asia/Seoul");
    }


    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
