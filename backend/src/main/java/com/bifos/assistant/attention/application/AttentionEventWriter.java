package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.domain.AttentionEvent;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.infra.AttentionEventRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 알리기의 지표 사건을 남긴다.
 *
 * <p>모든 사건 종류가 같은 규칙이다. 같은 사용자, 항목, 상태, 종류, 판정의 줄이 이미 있으면 넣지 않는다. 줄마다 새
 * 트랜잭션({@code REQUIRES_NEW})으로 따로 커밋해, 한 줄의 충돌이 다른 줄이나 부르는 쪽의 제어 줄을 되돌리지 않는다.
 *
 * <p>지표는 관측용이라 남기지 못해도 부르는 쪽으로 던지지 않는다. 로그에는 사용자 번호와 개수만 낸다.
 */
@Slf4j
@Component
public class AttentionEventWriter {

    private final AttentionEventRepository events;
    private final TransactionTemplate transactions;

    public AttentionEventWriter(AttentionEventRepository events, PlatformTransactionManager transactionManager) {
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 사건 한 줄을 넣는다. 같은 줄이 이미 있으면 넘어간다. */
    public void recordOnce(AttentionEvent event) {
        if (!insert(event)) {
            log.warn("attention event failed userId={} count={}", event.userId(), 1);
        }
    }

    /**
     * 응답에 실린 항목의 {@code SHOWN} 을 남긴다. 이미 남긴 {@code (itemKey, stateKey, attention)} 은 다시 넣지 않는다.
     *
     * @param shown 같은 사용자의 {@code SHOWN} 사건들
     */
    public void recordShown(Long userId, List<AttentionEvent> shown) {
        if (shown.isEmpty()) {
            return;
        }
        Map<String, AttentionEvent> wanted = new LinkedHashMap<>();
        shown.forEach(event -> wanted.putIfAbsent(identity(event.itemKey(), event.stateKey(), event.level()), event));
        Set<String> existing;
        try {
            existing = events
                    .findByUserIdAndEventTypeAndItemKeyIn(
                            userId,
                            AttentionEventType.SHOWN,
                            shown.stream().map(AttentionEvent::itemKey).collect(Collectors.toSet()))
                    .stream()
                    .map(event -> identity(event.itemKey(), event.stateKey(), event.level()))
                    .collect(Collectors.toSet());
        } catch (RuntimeException ex) {
            log.warn("attention event failed userId={} count={}", userId, wanted.size());
            return;
        }
        long failed = wanted.entrySet().stream()
                .filter(entry -> !existing.contains(entry.getKey()))
                .filter(entry -> !insert(entry.getValue()))
                .count();
        if (failed > 0) {
            log.warn("attention event failed userId={} count={}", userId, failed);
        }
    }

    /**
     * 새 트랜잭션에서 한 줄을 넣는다. 유일 제약에 걸리면 같은 줄이 먼저 들어간 것이라 성공으로 본다.
     *
     * @return 넣었거나 이미 있었으면 참, 그 밖의 이유로 남기지 못했으면 거짓
     */
    private boolean insert(AttentionEvent event) {
        try {
            transactions.executeWithoutResult(status -> events.saveAndFlush(event));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String identity(String itemKey, String stateKey, AttentionLevel level) {
        return itemKey + "|" + stateKey + "|" + level.name();
    }
}
