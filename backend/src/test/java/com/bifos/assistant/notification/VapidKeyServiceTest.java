package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.application.PushDeviceService;
import com.bifos.assistant.notification.application.PushRegistrationLock;
import com.bifos.assistant.notification.infra.PushDeviceRepository;
import org.springframework.transaction.PlatformTransactionManager;
import com.bifos.assistant.notification.application.VapidKeyService;
import com.bifos.assistant.notification.application.dto.PushRegistration;
import com.bifos.assistant.notification.application.dto.VapidStatus;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** VAPID 키를 새로 만들고 지울 때 기기와 알림이 어떻게 되는지 실제 DB 로 본다(서버 비밀값 스펙 REQ6, REQ7). */
@BackendIntegrationTest
class VapidKeyServiceTest {

    private static final TestSubscriptionKeys KEYS = TestSubscriptionKeys.create();

    @Autowired
    VapidKeyService service;

    @Autowired
    PushDeviceService devices;

    @Autowired
    VapidKeys keys;

    @Autowired
    PushDeviceRepository repository;

    @Autowired
    PushRegistrationLock lock;

    @Autowired
    NotificationService notificationService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdPeople = new ArrayList<>();
    private CurrentUser alice;
    private CurrentUser bob;
    private CurrentUser carol;


    @BeforeEach
    void setUp() {
        alice = user("alice");
        bob = user("bob");
        carol = user("carol");
    }


    @AfterEach
    void tearDown() {
        users.deleteAllById(createdUsers);
        people.deleteAllById(createdPeople);
    }


    @Test
    @DisplayName("다시 만들면 새 공개 키와 연락처가 보이고 모든 사용자의 기기가 비며 기기가 있던 사용자마다 알림이 하나 생긴다")
    void regenerateReleasesDevicesAndNotifies() {
        String before = keys.publicKeyBase64Url();
        devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/alice", before));
        devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/alice-2", before));
        devices.register(bob, registration("https://fcm.googleapis.com/fcm/send/bob", before));

        assertThat(service.status().deviceCount()).isEqualTo(3);
        int released = service.regenerate(alice.id(), "mailto:ops@example.invalid");

        assertThat(released).isEqualTo(2);
        VapidStatus status = service.status();
        assertThat(status.set()).isTrue();
        assertThat(status.publicKey()).isEqualTo(keys.publicKeyBase64Url()).isNotEqualTo(before);
        assertThat(status.subject()).isEqualTo("mailto:ops@example.invalid");
        assertThat(status.updatedBy()).isEqualTo(alice.id());
        assertThat(status.deviceCount()).isZero();
        assertThat(devices.list(alice)).isEmpty();
        assertThat(devices.list(bob)).isEmpty();
        assertThat(resetNotices(alice)).singleElement().satisfies(notice -> {
            assertThat(notice.title()).isEqualTo("알림 받기가 해제됐어요");
            assertThat(notice.targetType()).isEqualTo(NotificationTargetType.PUSH_SETTINGS);
            assertThat(notice.targetPublicId()).isNull();
        });
        assertThat(resetNotices(bob)).hasSize(1);
        assertThat(resetNotices(carol)).isEmpty();
    }


    @Test
    @DisplayName("다시 만든 뒤 옛 공개 키로 만든 구독은 등록되지 않고 새 공개 키로는 등록된다")
    void oldKeyRegistrationIsRejectedAfterRegenerate() {
        String before = keys.publicKeyBase64Url();
        service.regenerate(alice.id(), "mailto:ops@example.invalid");

        assertThatThrownBy(() -> devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/old", before)))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PUSH_KEY_CHANGED));
        assertThat(devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/new", keys.publicKeyBase64Url()))).isNotNull();
    }


    @Test
    @DisplayName("지우면 푸시가 꺼지고 등록이 설정되지 않음으로 거절되며 기기는 비고 알림은 남기지 않는다")
    void deleteTurnsPushOff() {
        devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/alice", keys.publicKeyBase64Url()));

        int released = service.delete(alice.id());

        assertThat(released).isEqualTo(1);
        assertThat(keys.enabled()).isFalse();
        assertThat(service.status().set()).isFalse();
        assertThat(devices.config().enabled()).isFalse();
        assertThat(devices.list(alice)).isEmpty();
        assertThat(resetNotices(alice)).isEmpty();
        assertThatThrownBy(() -> devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/again", "x")))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PUSH_NOT_CONFIGURED));
    }


    @Test
    @DisplayName("키가 없던 서버에서 만들면 푸시가 켜지고 만들 때 쓴 연락처가 남는다")
    void regenerateTurnsPushOn() {
        service.delete(alice.id());

        service.regenerate(alice.id(), "https://example.invalid/contact");

        assertThat(keys.enabled()).isTrue();
        assertThat(devices.config().enabled()).isTrue();
        assertThat(service.status().subject()).isEqualTo("https://example.invalid/contact");
    }


    @Test
    @DisplayName("연락처가 mailto: 나 https:// 주소가 아니면 거절하고 키와 기기는 그대로다")
    void rejectsBadSubject() {
        String before = keys.publicKeyBase64Url();
        devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/alice", before));

        for (String bad : new String[] {null, "", "ops@example.invalid", "mailto:", "http://example.invalid", "ftp://x"}) {
            assertThatThrownBy(() -> service.regenerate(alice.id(), bad))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(keys.publicKeyBase64Url()).isEqualTo(before);
        assertThat(devices.list(alice)).hasSize(1);
    }


    @Test
    @DisplayName("값 쓰기가 거절되면(KEK 없음) 키도 기기도 그대로다")
    void failedWriteKeepsKeyAndDevices() {
        String before = keys.publicKeyBase64Url();
        devices.register(alice, registration("https://fcm.googleapis.com/fcm/send/alice", before));
        ServerSecrets failing = mock(ServerSecrets.class);
        doThrow(new ApiException(ErrorCode.SECRET_STORE_UNAVAILABLE, "KEK is not configured"))
            .when(failing).write(anyString(), anyString(), anyMap(), any());
        VapidKeyService withoutKek = new VapidKeyService(failing, repository, lock, notificationService, transactionManager);

        assertThatThrownBy(() -> withoutKek.regenerate(alice.id(), "mailto:ops@example.invalid"))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SECRET_STORE_UNAVAILABLE));

        assertThat(keys.publicKeyBase64Url()).isEqualTo(before);
        assertThat(devices.list(alice)).hasSize(1);
        assertThat(resetNotices(alice)).isEmpty();
    }


    private List<Notification> resetNotices(CurrentUser user) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(user.id(), PageRequest.ofSize(20)).stream()
            .filter(notice -> notice.kind() == NotificationKind.PUSH_DEVICES_RESET)
            .toList();
    }


    private static PushRegistration registration(String endpoint, String publicKey) {
        return new PushRegistration(endpoint, KEYS.p256dh(), KEYS.auth(), "PC", "Asia/Seoul", publicKey);
    }


    private CurrentUser user(String name) {
        String email = name + "-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, name, 1L, UserRole.MEMBER));
        createdPeople.add(people.save(AllowedPerson.of(email, name, "push-" + UUID.randomUUID())).id());
        createdUsers.add(saved.id());
        return new CurrentUser(saved.id(), email, name, 1L, UserRole.MEMBER);
    }
}
