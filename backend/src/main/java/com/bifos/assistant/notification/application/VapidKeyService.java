package com.bifos.assistant.notification.application;

import com.bifos.assistant.crypto.domain.ServerSecretRecord;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.notification.application.dto.VapidStatus;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.PushDeviceRepository;
import com.bifos.assistant.notification.infra.PushDevicesCodec;
import com.bifos.assistant.notification.infra.GeneratedVapidKeys;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자가 웹 푸시 VAPID 키를 서버에서 새로 만들거나 지운다(서버 비밀값 스펙 REQ6, REQ7).
 *
 * <p>키가 바뀌면 푸시 서비스가 옛 구독에 403 을 답해 자동 정리가 기기를 지우지 못한다. 그래서 키를 바꾸거나 지울 때 모든 사용자의 등록
 * 기기를 함께 비운다. 값 쓰기와 기기 비우기는 {@link PushRegistrationLock} 안의 한 트랜잭션이다. 잠금을 놓은 뒤의 등록은 구독한 공개 키가
 * 지금 키와 다르면 거절된다. 해제 알림은 커밋 뒤 사용자마다 따로 만든다. 알림이 실패해도 키는 바뀐 채 경고만 남긴다.
 */
@Slf4j
@Service
public class VapidKeyService {

    static final String RESET_TITLE = "알림 받기가 해제됐어요";
    static final String RESET_BODY = "설정의 「알림」 에서 기기를 다시 등록해 주세요";
    static final int SUBJECT_MAX = 200;

    private final ServerSecrets secrets;
    private final PushDeviceRepository repository;
    private final PushRegistrationLock lock;
    private final NotificationService notifications;
    private final TransactionTemplate transaction;


    public VapidKeyService(
        ServerSecrets secrets,
        PushDeviceRepository repository,
        PushRegistrationLock lock,
        NotificationService notifications,
        PlatformTransactionManager transactionManager) {
        this.secrets = secrets;
        this.repository = repository;
        this.lock = lock;
        this.notifications = notifications;
        this.transaction = new TransactionTemplate(transactionManager);
    }


    /** 화면이 보이는 지금 상태다. 비밀 키는 담지 않고 공개 키와 연락처만 있다. */
    public VapidStatus status() {
        Optional<ServerSecretRecord> row = secrets.find(VapidKeys.SECRET_NAME);
        // 확인 창이 「기기 N대」 를 보이도록 기기 수를 센다. 관리자 화면이 열릴 때만 돌고 사용자 수가 적다.
        int devices = repository.findUserIdsWithDevices().stream()
            .mapToInt(userId -> PushDevicesCodec.decode(repository.findDevicesJson(userId).orElse(null)).newestFirst().size())
            .sum();
        if (row.isEmpty() || !row.get().set()) {
            return new VapidStatus(false, null, null, row.map(ServerSecretRecord::updatedBy).orElse(null),
                row.map(ServerSecretRecord::updatedAt).orElse(null), devices);
        }
        ServerSecretRecord found = row.get();
        return new VapidStatus(true, text(found.meta().get("publicKey")), text(found.meta().get("subject")),
            found.updatedBy(), found.updatedAt(), devices);
    }


    /**
     * 키 쌍을 새로 만들어 저장하고 모든 사용자의 등록 기기를 비운다. 기기가 있던 사용자마다 웹 안 알림을 하나 남긴다.
     *
     * @param subject 푸시 서비스가 연락할 곳. {@code mailto:} 나 {@code https:} 주소
     * @return 기기가 해제된 사용자 수
     * @throws ApiException KEK 가 없으면 {@code SECRET_STORE_UNAVAILABLE}, 연락처가 이상하면 {@code VALIDATION_FAILED}
     */
    public int regenerate(Long adminId, String subject) {
        return regenerate(adminId, subject, null, false);
    }


    /**
     * 화면이 확인 창을 보일 때 읽은 키가 지금도 그대로일 때만 만든다. 그새 다른 관리자가 키를 만들었으면 확인 창 없이 기기가 해제되므로
     * 거절한다.
     *
     * @param expectedPublicKey 화면이 읽은 공개 키. 키가 없던 화면이면 null
     * @throws ApiException 지금 키가 화면이 읽은 것과 다르면 {@code VAPID_STATE_CHANGED}
     */
    public int regenerate(Long adminId, String subject, String expectedPublicKey) {
        return regenerate(adminId, subject, expectedPublicKey, true);
    }


    private int regenerate(Long adminId, String subject, String expectedPublicKey, boolean checkExpected) {
        String normalized = validSubject(subject);
        GeneratedVapidKeys generated = VapidKeys.generate(normalized);
        Map<String, Object> meta = Map.of("publicKey", generated.publicKey(), "subject", normalized);
        List<Long> released = lock.run(() -> transaction.execute(status -> {
            if (checkExpected && !Objects.equals(currentPublicKey(), expectedPublicKey)) {
                throw new ApiException(ErrorCode.VAPID_STATE_CHANGED, "VAPID key changed since the screen was read");
            }
            // 값 쓰기가 KEK 없음으로 거절되면 기기는 아직 그대로이고 트랜잭션이 되돌아간다.
            secrets.write(VapidKeys.SECRET_NAME, generated.sealed(), meta, adminId);
            return releaseAllDevices();
        }));
        notifyReleased(released);
        return released.size();
    }


    /** 키를 지워 푸시를 끄고 모든 사용자의 등록 기기를 비운다. 푸시를 받을 수 없게 되므로 알림은 남기지 않는다. @return 기기가 해제된 사용자 수 */
    public int delete(Long adminId) {
        List<Long> released = lock.run(() -> transaction.execute(status -> {
            secrets.clear(VapidKeys.SECRET_NAME, adminId);
            return releaseAllDevices();
        }));
        return released.size();
    }


    private String currentPublicKey() {
        return secrets.find(VapidKeys.SECRET_NAME)
            .filter(ServerSecretRecord::set)
            .map(row -> text(row.meta().get("publicKey")))
            .orElse(null);
    }


    private List<Long> releaseAllDevices() {
        List<Long> owners = repository.findUserIdsWithDevices();
        repository.clearAllDevices();
        return owners;
    }


    private void notifyReleased(List<Long> userIds) {
        for (Long userId : userIds) {
            try {
                transaction.executeWithoutResult(status -> notifications.notify(
                    userId,
                    NotificationKind.PUSH_DEVICES_RESET,
                    RESET_TITLE,
                    RESET_BODY,
                    new NotificationTarget(NotificationTargetType.PUSH_SETTINGS, null)));
            } catch (RuntimeException ex) {
                // 키는 이미 바뀌었다. 알림 하나가 실패해도 나머지 사용자에게는 남긴다.
                log.warn("VAPID 키를 바꾼 뒤 해제 알림을 남기지 못했다 userId={} exception={}", userId, ex.getClass().getName());
            }
        }
    }


    private static String validSubject(String subject) {
        String value = subject == null ? "" : subject.strip();
        if (value.length() > SUBJECT_MAX || !validUri(value)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "subject must be a mailto: or https: address");
        }
        return value;
    }


    /** RFC 8292 의 {@code sub} 이다. https 는 호스트가, mailto 는 {@code 이름@호스트} 가 있어야 한다. */
    private static boolean validUri(String value) {
        try {
            URI uri = new URI(value);
            if ("https".equals(uri.getScheme())) {
                return uri.getHost() != null && !uri.getHost().isBlank();
            }
            if ("mailto".equals(uri.getScheme())) {
                String address = uri.getSchemeSpecificPart();
                int at = address == null ? -1 : address.indexOf('@');
                return at > 0 && at < address.length() - 1 && address.indexOf('@', at + 1) < 0 && !address.contains("?");
            }
            return false;
        } catch (URISyntaxException ex) {
            return false;
        }
    }


    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }
}
