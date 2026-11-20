package com.bifos.assistant.notification.application;

import com.bifos.assistant.notification.application.dto.PushConfig;
import com.bifos.assistant.notification.application.dto.PushRegistration;
import com.bifos.assistant.notification.config.PushProperties;
import com.bifos.assistant.notification.domain.PushDevice;
import com.bifos.assistant.notification.domain.PushDevices;
import com.bifos.assistant.notification.domain.PushEndpointPolicy;
import com.bifos.assistant.notification.infra.PushDeviceRepository;
import com.bifos.assistant.notification.infra.P256;
import com.bifos.assistant.notification.infra.PushDevicesCodec;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 웹 푸시 기기를 등록하고 해제하고 읽는다(ADR-20261120 / web-push).
 *
 * <p>계약은 {@code docs/features/attention/design.md} 의 「저장」 과 「등록의 순서」 가 갖는다. 목록을 바꾸는 일은 모두
 * {@link PushRegistrationLock} 을 잡은 뒤 새 트랜잭션을 열고, 바꿀 사용자 행을 번호 순으로 잠그고 다시 읽은 뒤 쓴다. 새 트랜잭션이라 커밋이
 * 끝난 바깥 트랜잭션에 붙지 않는다.
 */
@Service
public class PushDeviceService {

    static final int NAME_MAX = 40;
    static final int P256DH_MAX = 200;
    static final int AUTH_MAX = 100;
    static final int TIME_ZONE_MAX = 64;
    static final int AUTH_BYTES = 16;

    private final PushDeviceRepository repository;
    private final PushRegistrationLock lock;
    private final VapidKeys keys;
    private final PushEndpointPolicy policy;
    private final int maxDevices;
    private final Clock clock;
    private final TransactionTemplate newTransaction;


    public PushDeviceService(
        PushDeviceRepository repository,
        PushRegistrationLock lock,
        VapidKeys keys,
        PushProperties properties,
        Clock clock,
        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.lock = lock;
        this.keys = keys;
        this.policy = new PushEndpointPolicy(properties.allowedEndpointHosts());
        this.maxDevices = properties.maxDevices();
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }


    /** 화면이 구독을 만들 때 읽는 서버 설정이다. */
    public PushConfig config() {
        return new PushConfig(keys.enabled(), keys.enabled() ? keys.publicKeyBase64Url() : null);
    }


    /**
     * 이 브라우저를 내 기기로 등록한다. 같은 주소가 내 목록에 있으면 키와 이름, 시간대, 등록 시각을 바꾸고, 다른 사용자 목록에 있으면 그 사용자
     * 목록에서 빼고 나에게 옮긴다.
     *
     * @return 등록한 기기
     */
    public PushDevice register(CurrentUser user, PushRegistration registration) {
        if (!keys.enabled()) {
            throw new ApiException(ErrorCode.PUSH_NOT_CONFIGURED, "web push is not configured on this server");
        }
        validate(registration);
        String hash = Sha256.hex(registration.endpoint());
        PushDevice candidate = new PushDevice(
            UUID.randomUUID(),
            registration.endpoint(),
            hash,
            registration.p256dh(),
            registration.auth(),
            registration.name().strip(),
            registration.timeZone(),
            clock.instant());
        return lock.run(() -> newTransaction.execute(status -> {
            TreeSet<Long> owners = new TreeSet<>(repository.findUserIdsHoldingHash(hash));
            owners.add(user.id());
            Map<Long, PushDevices> locked = new TreeMap<>();
            for (Long owner : owners) {
                lockAndRead(owner).ifPresent(devices -> locked.put(owner, devices));
            }
            if (!locked.containsKey(user.id())) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "the requesting user no longer exists");
            }
            locked.forEach((owner, theirs) -> {
                if (!owner.equals(user.id()) && theirs.containsHash(hash)) {
                    repository.storeDevices(owner, PushDevicesCodec.encode(theirs.removeByHash(hash)));
                }
            });
            PushDevices mine = locked.get(user.id()).upsert(candidate, maxDevices);
            repository.storeDevices(user.id(), PushDevicesCodec.encode(mine));
            return mine.findByHash(hash);
        }));
    }


    /** 내 기기 목록이다. 최근 등록 순이다. */
    @Transactional(readOnly = true)
    public List<PushDevice> list(CurrentUser user) {
        return read(user.id()).newestFirst();
    }


    /** 그 사용자의 기기 목록이다. 보내기가 잠그지 않고 읽는다. */
    @Transactional(readOnly = true)
    public PushDevices devicesOf(Long userId) {
        return read(userId);
    }


    /** 이 브라우저의 주소와 같은 내 기기를 뺀다. 없으면 아무것도 하지 않는다. 로그아웃과 「그만 받기」 가 부른다. */
    public void unregisterCurrent(CurrentUser user, String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.length() > PushEndpointPolicy.MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "endpoint is required");
        }
        String hash = Sha256.hex(endpoint);
        mutate(user.id(), devices -> devices.removeByHash(hash));
    }


    /**
     * 푸시 서비스가 구독이 없다고 답한 기기를 뺀다. 보낼 때 읽은 기기와 번호와 등록 시각이 같을 때만 뺀다. 그 사이 같은 주소로 다시
     * 등록했거나 다른 사용자에게 옮겨졌거나 이미 빠졌으면 아무것도 하지 않는다(rule-68).
     */
    public void removeIfUnchanged(Long userId, PushDevice sent) {
        mutate(userId, devices -> devices.devices().stream()
            .anyMatch(device -> device.id().equals(sent.id()) && device.registeredAt().equals(sent.registeredAt()))
                ? devices.removeById(sent.id())
                : devices);
    }


    /** 그 사용자의 기기를 모두 지운다. 관리자가 접근을 끄면 부른다. */
    public void clearAll(Long userId) {
        mutate(userId, devices -> PushDevices.empty());
    }


    private void mutate(Long userId, UnaryOperator<PushDevices> change) {
        lock.run(() -> newTransaction.execute(status -> {
            Optional<PushDevices> found = lockAndRead(userId);
            if (found.isEmpty()) {
                return null;
            }
            PushDevices current = found.get();
            PushDevices next = change.apply(current);
            if (!next.equals(current)) {
                repository.storeDevices(userId, PushDevicesCodec.encode(next));
            }
            return null;
        }));
    }


    /** 그 사용자 행을 잠그고 최신 목록을 읽는다. 사용자가 없으면 빈 값이다. */
    private Optional<PushDevices> lockAndRead(Long userId) {
        List<String> rows = repository.lockAndReadDevicesJson(userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(PushDevicesCodec.decode(rows.getFirst()));
    }


    private PushDevices read(Long userId) {
        return PushDevicesCodec.decode(repository.findDevicesJson(userId).orElse(null));
    }


    private void validate(PushRegistration registration) {
        if (registration == null || !policy.allows(registration.endpoint())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "endpoint is not an allowed push service");
        }
        requireLength("keys.p256dh", registration.p256dh(), P256DH_MAX);
        requireLength("keys.auth", registration.auth(), AUTH_MAX);
        requireKeys(registration.p256dh(), registration.auth());
        String name = registration.name() == null ? "" : registration.name().strip();
        requireLength("name", name, NAME_MAX);
        requireLength("timeZone", registration.timeZone(), TIME_ZONE_MAX);
        try {
            ZoneId.of(registration.timeZone());
        } catch (DateTimeException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "timeZone is not a known zone");
        }
    }


    /** 보낼 때에야 깨진 것을 알면 기기가 목록에 남은 채 늘 건너뛰어진다. 그래서 등록할 때 곡선 위의 점과 16바이트 인증 값인지 본다. */
    private static void requireKeys(String p256dh, String auth) {
        try {
            P256.publicKey(Base64.getUrlDecoder().decode(p256dh));
            if (Base64.getUrlDecoder().decode(auth).length != AUTH_BYTES) {
                throw new IllegalArgumentException("auth must be 16 bytes");
            }
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "keys are not a valid push subscription");
        }
    }


    private static void requireLength(String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must be 1 to " + max + " characters");
        }
    }
}
