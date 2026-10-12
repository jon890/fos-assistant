package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 기존 평문을 줄마다 다시 확인해 이관한다. 실패한 줄은 다음 기동에서 다시 시도한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorActionBodyBackfill {
    private final ConnectorActionRepository actions;
    private final ConnectorActionBodies bodies;
    private final AppUserRepository users;
    private final PlatformTransactionManager transactionManager;


    @EventListener(ApplicationReadyEvent.class)
    @Order(6)
    public void backfill() {
        var candidates = actions.findPlainBodyIds();
        if (!bodies.enabled()) {
            if (!candidates.isEmpty()) {
                log.warn("승인 본문 암호화를 사용할 수 없다 remaining={}", candidates.size());
            }
            return;
        }
        var tx = new TransactionTemplate(transactionManager);
        int sealed = 0;
        int failed = 0;
        for (Long id : candidates) {
            try {
                var before = actions.findById(id).orElse(null);
                if (before == null) {
                    continue;
                }
                Boolean changed = tx.execute(status -> {
                    users.findByIdForUpdate(before.userId()).orElseThrow();
                    var action = actions.findByPublicIdForUpdate(before.publicId()).orElseThrow();
                    boolean pending = (action.argsKeyId() == null && action.storedArgsJson() != null)
                        || (action.resultKeyId() == null && action.storedResultText() != null);
                    if (action.argsKeyId() == null && action.storedArgsJson() != null) {
                        bodies.sealArgs(action, action.storedArgsJson());
                    }
                    bodies.sealResult(action);
                    return pending;
                });
                if (Boolean.TRUE.equals(changed)) {
                    sealed++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("승인 본문 이관 실패 actionId={} kind={}", id, ex.getClass().getSimpleName());
            }
        }
        log.info("승인 본문 이관 sealed={} failed={} remaining={}", sealed, failed, actions.findPlainBodyIds().size());
    }
}
