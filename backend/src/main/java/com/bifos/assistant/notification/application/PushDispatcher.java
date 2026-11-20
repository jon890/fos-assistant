package com.bifos.assistant.notification.application;

import com.bifos.assistant.notification.application.dto.NotificationCreated;
import com.bifos.assistant.notification.config.PushProperties;
import com.bifos.assistant.notification.domain.PushDevice;
import com.bifos.assistant.notification.domain.PushEndpointPolicy;
import com.bifos.assistant.notification.infra.PushDeviceRepository;
import com.bifos.assistant.notification.infra.PushResponse;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.notification.infra.VapidSigner;
import com.bifos.assistant.notification.infra.WebPushClient;
import com.bifos.assistant.notification.infra.WebPushEncryptor;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림이 커밋되면 그 사용자의 기기로 웹 푸시를 보낸다(ADR-20261120 / web-push).
 *
 * <p>계약은 {@code docs/features/attention/design.md} 의 「보내기」 가 갖는다. 커밋 뒤 사건에서는 가상 스레드 하나만 띄우고 돌아가므로
 * 알림을 만든 일은 보내기를 기다리지 않는다. 스레드 안의 실패는 로그로 끝난다. 로그에는 사용자 번호, 기기 번호, 상태 코드, 시도 횟수만
 * 남기고 주소, 키, 제목, 본문은 남기지 않는다.
 */
@Slf4j
@Component
public class PushDispatcher {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PushDeviceService devices;
    private final PushDeviceRepository repository;
    private final VapidKeys keys;
    private final VapidSigner signer;
    private final WebPushEncryptor encryptor;
    private final WebPushClient client;
    private final BackgroundTasks background;
    private final Clock clock;
    private final PushEndpointPolicy policy;
    private final List<Duration> retryDelays;
    private final Duration retryAfterMax;


    public PushDispatcher(
        PushDeviceService devices,
        PushDeviceRepository repository,
        VapidKeys keys,
        VapidSigner signer,
        WebPushEncryptor encryptor,
        WebPushClient client,
        BackgroundTasks background,
        Clock clock,
        PushProperties properties) {
        this.devices = devices;
        this.repository = repository;
        this.keys = keys;
        this.signer = signer;
        this.encryptor = encryptor;
        this.client = client;
        this.background = background;
        this.clock = clock;
        this.policy = new PushEndpointPolicy(properties.allowedEndpointHosts());
        this.retryDelays = properties.retryDelays();
        this.retryAfterMax = properties.retryAfterMax();
    }


    /** 알림을 저장한 트랜잭션이 커밋된 뒤에만 돈다. 되돌아간 트랜잭션은 사건이 없다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(NotificationCreated event) {
        try {
            if (!keys.enabled()) {
                return;
            }
            background.start("push-" + event.publicId(), () -> deliver(event));
        } catch (RuntimeException ex) {
            log.warn("push dispatch failed to start userId={} error={}", event.userId(), ex.getClass().getSimpleName());
        }
    }


    private void deliver(NotificationCreated event) {
        try {
            List<PushDevice> targets = devices.devicesOf(event.userId()).newestFirst();
            if (targets.isEmpty()) {
                return;
            }
            byte[] payload = payload(event);
            if (payload.length > WebPushEncryptor.MAX_PLAINTEXT) {
                log.warn("push skipped, payload too large userId={} bytes={}", event.userId(), payload.length);
                return;
            }
            for (PushDevice device : targets) {
                try {
                    deliverTo(event.userId(), device, payload);
                } catch (RuntimeException ex) {
                    log.warn("push delivery failed userId={} deviceId={} error={}",
                        event.userId(), device.id(), ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("push delivery failed userId={} error={}", event.userId(), ex.getClass().getSimpleName());
        }
    }


    private void deliverTo(Long userId, PushDevice device, byte[] payload) {
        if (!policy.allows(device.endpoint())) {
            log.warn("push skipped, endpoint not allowed userId={} deviceId={}", userId, device.id());
            return;
        }
        if (heldByOthers(userId, device)) {
            log.warn("push skipped, device also held by another user userId={} deviceId={}", userId, device.id());
            return;
        }
        byte[] body;
        try {
            body = encryptor.encrypt(payload, B64.decode(device.p256dh()), B64.decode(device.auth()));
        } catch (RuntimeException ex) {
            log.warn("push skipped, device keys unusable userId={} deviceId={} error={}",
                userId, device.id(), ex.getClass().getSimpleName());
            return;
        }
        for (int attempt = 0;; attempt++) {
            PushResponse response = client.send(device.endpoint(), signer.authorization(device.endpoint(), clock.instant()), body);
            if (response.delivered()) {
                return;
            }
            if (response.gone()) {
                devices.removeIfUnchanged(userId, device);
                log.info("push device removed, subscription gone userId={} deviceId={} status={}", userId, device.id(), response.status());
                return;
            }
            if (!response.transientFailure() || attempt >= retryDelays.size()) {
                log.warn("push dropped userId={} deviceId={} status={} attempts={}", userId, device.id(), response.status(), attempt + 1);
                return;
            }
            if (!pause(waitFor(response, retryDelays.get(attempt))) || heldByOthers(userId, device)) {
                return;
            }
        }
    }


    private boolean heldByOthers(Long userId, PushDevice device) {
        return repository.findUserIdsHoldingHash(device.endpointHash()).stream().anyMatch(owner -> !owner.equals(userId));
    }


    private Duration waitFor(PushResponse response, Duration delay) {
        boolean honored = response.status() == 429 || response.status() == 503;
        if (!honored || response.retryAfter() == null) {
            return delay;
        }
        return response.retryAfter().compareTo(retryAfterMax) > 0 ? retryAfterMax : response.retryAfter();
    }


    private static boolean pause(Duration wait) {
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }


    private static byte[] payload(NotificationCreated event) {
        Payload payload = new Payload(event.title(), event.body(), "/n/" + event.publicId(), event.publicId().toString());
        return JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    }


    private record Payload(String title, String body, String url, String tag) {
    }
}
