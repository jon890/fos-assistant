package com.bifos.assistant.task.application;

import com.bifos.assistant.chat.conversation.application.ConversationPublicIdLookup;
import com.bifos.assistant.connector.connection.application.ConnectorEventWatches;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.proactive.check.application.dto.EventBatch;
import com.bifos.assistant.proactive.check.application.dto.EventCheckEnded;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사건 감지의 알림을 만든다(ADR-20261105 / event-watch). 무엇을 언제 알리는지는 {@code docs/features/proactive.md} 의 「사건 감지」 가,
 * 제목과 본문은 이 클래스가 갖는다.
 *
 * <p>알림은 원인의 상태 갱신과 한 트랜잭션이다. 바인딩 줄을 잠근 트랜잭션 안에서 상태를 쓰고 알림을 만든다. 제목과 본문에는 수와 원천 이름만
 * 둔다. 알림 표는 평문이라 항목의 제목이나 내용을 넣지 않는다(스펙 DECISION23).
 */
@Component
@RequiredArgsConstructor
public class EventWatchNotices {

    static final String FAILED_BODY = "외부 서비스 연결에서 다시 연결해 주세요";

    private final ConnectorEventWatches watches;
    private final NotificationService notifications;
    private final ConversationPublicIdLookup conversations;
    private final TransactionTemplate transactions;


    /**
     * 답을 남기고 끝난 사건 실행을 알린다. 그 시간대의 조용한 시간이면 미뤄 두고, 아니면 알릴 항목이 있을 때 한 건을 만든다.
     *
     * <p>살펴보기의 끝 정리 스레드에서 동기로 불리고 새 트랜잭션을 연다. 그 사이 바인딩을 껐으면 미룰 자리와 시간대가 없어 알릴 항목이 있으면 바로
     * 알린다. 사용자가 볼 답은 이미 대화에 있다.
     */
    @EventListener
    public void ended(EventCheckEnded ended) {
        EventBatch batch = ended.batch();
        transactions.executeWithoutResult(status -> {
            Optional<EventWatchState> state = watches.update(batch.sourceId(),
                current -> ProactiveQuietHours.quiet(current.zone(), ended.endedAt())
                    ? current.defer(batch.newCount(), ended.notifyCount(), ended.conversationId(), batch.sourceTitle(),
                        batch.noun())
                    : current);
            boolean deferred = state.map(current -> ProactiveQuietHours.quiet(current.zone(), ended.endedAt())).orElse(false);
            if (deferred || ended.notifyCount() <= 0) {
                return;
            }
            notifications.notify(
                ended.userId(),
                NotificationKind.EVENT_CHECK_REPORTED,
                reportTitle("", batch.noun(), batch.newCount(), ended.notifyCount()),
                batch.sourceTitle(),
                conversation(ended.conversationId()));
        });
    }


    /**
     * 조용한 시간이 지났으면 미룬 알림을 비우고, 알릴 항목이 있었으면 한 건으로 묶어 낸다. 부르는 쪽 트랜잭션 안에서 부른다. 원천 이름과 항목
     * 이름은 미룰 때 적어 둔 것을 쓴다.
     */
    public void flushDeferred(Long bindingId, Long userId, Instant now) {
        AtomicReference<EventWatchState.Deferred> flushed = new AtomicReference<>();
        watches.update(bindingId, current -> {
            if (current.deferred() == null || ProactiveQuietHours.quiet(current.zone(), now)) {
                return current;
            }
            flushed.set(current.deferred());
            return current.withoutDeferred();
        });
        EventWatchState.Deferred deferred = flushed.get();
        if (deferred == null || deferred.notifyCount() <= 0) {
            return;
        }
        notifications.notify(
            userId,
            NotificationKind.EVENT_CHECK_REPORTED,
            reportTitle("밤사이 ", deferred.noun(), deferred.newCount(), deferred.notifyCount()),
            deferred.sourceTitle(),
            conversation(deferred.conversationId()));
    }


    /**
     * 연결 문제의 연속 실패가 정한 횟수에 닿았고 아직 알리지 않았으면 다시 연결 알림을 한 번 낸다. 부르는 쪽 트랜잭션 안에서 실패를 적은 뒤
     * 부른다. 조용한 시간이면 알리지 않고 표시도 두지 않아, 07시 뒤에도 실패가 이어지면 그때 처음 알린다(설계 DECISION10).
     *
     * @param threshold 알리는 연속 실패 수
     */
    public void failed(Long bindingId, Long userId, String sourceTitle, int threshold, Instant now) {
        AtomicBoolean reached = new AtomicBoolean();
        watches.update(bindingId, current -> {
            if (current.failures() < threshold || current.failureNotified() || ProactiveQuietHours.quiet(current.zone(), now)) {
                return current;
            }
            reached.set(true);
            return current.failureNoticed();
        });
        if (!reached.get()) {
            return;
        }
        notifications.notify(
            userId,
            NotificationKind.EVENT_WATCH_FAILED,
            sourceTitle + " 다시 연결이 필요해요",
            FAILED_BODY,
            new NotificationTarget(NotificationTargetType.CONNECTIONS, null));
    }


    /** 「새 메일 3건 중 1건 확인이 필요해요」 꼴의 제목이다. 수만 싣는다. */
    static String reportTitle(String prefix, String noun, int newCount, int notifyCount) {
        return prefix + "새 " + noun + " " + newCount + "건 중 " + notifyCount + "건 확인이 필요해요";
    }


    /** 그 점검 대화로 가는 갈 곳이다. 지워졌으면 갈 곳이 없다. */
    private NotificationTarget conversation(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        UUID publicId = conversations.publicIdsOf(List.of(conversationId)).get(conversationId);
        return publicId == null ? null : new NotificationTarget(NotificationTargetType.CONVERSATION, publicId);
    }
}
