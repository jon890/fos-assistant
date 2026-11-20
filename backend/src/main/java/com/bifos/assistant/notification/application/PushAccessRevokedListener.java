package com.bifos.assistant.notification.application;

import com.bifos.assistant.shared.auth.UserAccessRevoked;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 관리자가 사용자의 접근을 끄면 그 사용자의 웹 푸시 기기를 모두 지운다(스펙 {@code docs/features/attention/spec.md} REQ9).
 *
 * <p>정리 예약 전까지 사용자 줄이 남아 있어도 꺼진 사용자의 기기로 알림 제목이 가지 않게 한다. 다시 켜도 기기는 돌아오지 않는다.
 *
 * <p>끈 요청이 커밋된 뒤에 돈다. 그 자리에는 커밋이 끝난 트랜잭션이 아직 묶여 있어 {@code NOT_SUPPORTED} 로 떼어 둔다. 지우기는
 * {@link PushDeviceService#clearAll} 이 새 트랜잭션에서 한다. 실패하면 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushAccessRevokedListener {

    private final PushDeviceService devices;


    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onAccessRevoked(UserAccessRevoked event) {
        try {
            devices.clearAll(event.userId());
        } catch (RuntimeException ex) {
            log.warn("push devices clear on access removal failed userId={} error={}", event.userId(), ex.getClass().getSimpleName());
        }
    }
}
