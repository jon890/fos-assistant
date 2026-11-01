package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.dto.AttentionReaction;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.feedback.application.ReactionLedger;
import com.bifos.assistant.feedback.application.dto.ControlEntry;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 알리기의 지표 사건(노출, 열기, 동작)을 반응 기록에 남긴다.
 *
 * <p>모든 사건 종류가 같은 규칙이다. 같은 사용자, 항목, 상태, 종류, 판정의 줄이 이미 있으면 넣지 않는다. 줄마다 새
 * 트랜잭션({@code REQUIRES_NEW})으로 따로 커밋해, 한 줄의 충돌이 다른 줄이나 부르는 쪽의 제어 사건을 되돌리지 않는다.
 *
 * <p>지표는 관측용이라 남기지 못해도 부르는 쪽으로 던지지 않는다. 숨김, 미루기, 복원은 이 클래스가 아니라 {@code
 * AttentionControlService} 가 필수 쓰기로 남긴다. 로그에는 사용자 번호, 개수, 예외 클래스 이름만 낸다. 제목과 항목 열쇠는 내지 않는다.
 */
@Slf4j
@Component
public class AttentionEventWriter {

    private final ReactionLedger ledger;
    private final TransactionTemplate transactions;


    public AttentionEventWriter(ReactionLedger ledger, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }


    /** 사건 한 줄을 넣는다. 같은 줄이 이미 있으면 넘어간다. */
    public void recordOnce(AttentionReaction reaction) {
        String failure = insert(reaction);
        if (failure != null) {
            log.warn("attention event failed userId={} count={} kind={}", reaction.userId(), 1, failure);
        }
    }


    /**
     * 응답에 실린 항목의 {@code SHOWN} 을 남긴다. 이미 남긴 {@code (itemKey, stateKey, attention)} 은 다시 넣지 않는다.
     *
     * @param shown 같은 사용자의 {@code SHOWN} 사건들
     */
    public void recordShown(Long userId, List<AttentionReaction> shown) {
        if (shown.isEmpty()) {
            return;
        }
        Map<String, AttentionReaction> wanted = new LinkedHashMap<>();
        shown.forEach(reaction -> wanted.putIfAbsent(AttentionReactionCodec.eventKeyOf(reaction), reaction));
        Set<String> existing;
        try {
            existing = ledger.existingKeys(userId, wanted.keySet());
        } catch (RuntimeException ex) {
            log.warn(
                "attention event failed userId={} count={} kind={}",
                userId,
                wanted.size(),
                ex.getClass().getSimpleName());
            return;
        }
        List<String> failures = wanted.entrySet().stream()
            .filter(entry -> !existing.contains(entry.getKey()))
            .map(entry -> insert(entry.getValue()))
            .filter(Objects::nonNull)
            .toList();
        if (!failures.isEmpty()) {
            log.warn(
                "attention event failed userId={} count={} kind={}",
                userId,
                failures.size(),
                String.join(",", new TreeSet<>(failures)));
        }
    }


    /**
     * 새 트랜잭션에서 한 줄을 넣는다. 유일 제약에 걸리면 같은 줄이 먼저 들어간 것이라 성공으로 본다.
     *
     * @return 넣었거나 이미 있었으면 null, 그 밖의 이유로 남기지 못했으면 그 예외 클래스의 이름
     */
    private String insert(AttentionReaction reaction) {
        FeedbackEntry entry = FeedbackEntry.ofKey(
            reaction.userId(),
            FeedbackSubjectType.ATTENTION_ITEM,
            reaction.itemKey(),
            AttentionReactionCodec.feedbackTypeOf(reaction.type()),
            reaction.type() == AttentionEventType.SHOWN
                ? FeedbackActor.SYSTEM
                : FeedbackActor.USER,
            reaction.at())
            .version(reaction.stateKey())
            .reason(AttentionReactionCodec.reasonOf(reaction));
        try {
            transactions.executeWithoutResult(
                status -> ledger.append(new ControlEntry(entry, null, null, AttentionReactionCodec.eventKeyOf(reaction))));
            return null;
        } catch (DataIntegrityViolationException ex) {
            return null;
        } catch (RuntimeException ex) {
            return ex.getClass().getSimpleName();
        }
    }
}
