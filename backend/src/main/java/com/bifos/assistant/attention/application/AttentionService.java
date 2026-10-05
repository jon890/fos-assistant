package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionControl;
import com.bifos.assistant.attention.application.model.AttentionSnapshot;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.domain.AttentionControlEntry;
import com.bifos.assistant.attention.domain.AttentionEvent;
import com.bifos.assistant.attention.domain.type.AttentionAction;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.attention.infra.AttentionControlRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 요청자의 원래 기록을 읽어 지금 화면의 카드 다섯을 계산한다. 판정 결과를 저장하지 않는다(ADR-072).
 *
 * <p>트랜잭션을 열지 않는다. 후보마다 읽는 쪽이 자기 트랜잭션으로 돌아, 한 쪽의 실패가 다른 쪽의 읽기를 되돌리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttentionService {

    private final List<AttentionCandidates> sources;
    private final AttentionJudge judge;
    private final AttentionProperties properties;
    private final AttentionControlRepository controls;
    private final AttentionEventWriter events;
    private final Clock clock;

    /**
     * 카드 다섯과 건수를 계산한다. 읽지 못한 기록이 든 카드만 {@code UNAVAILABLE} 이다.
     *
     * <p>응답에 실린 {@code NOW} 와 {@code LATER} 항목마다 {@code SHOWN} 사건을 남긴다. 남기지 못해도 응답은 낸다.
     */
    public AttentionView view(CurrentUser user) {
        AttentionSnapshot snapshot = snapshot(user);
        events.recordShown(user.id(), shown(user, snapshot));
        return AttentionView.of(snapshot.now(), snapshot.cards());
    }

    /** {@link #view} 와 같은 계산의 맨 위 건수다. 사이드바의 수가 카드 배지의 합과 늘 같다. 사건을 남기지 않는다. */
    public int summary(CurrentUser user) {
        AttentionSnapshot snapshot = snapshot(user);
        return AttentionView.of(snapshot.now(), snapshot.cards()).nowCount();
    }

    /**
     * 억제 전 후보와, 요청자의 숨기기와 미루기를 적용한 판정을 함께 낸다. 사건을 남기지 않는다.
     *
     * <p>지금 화면과 사용자 제어가 같이 쓴다. 숨기기와 미루기는 억제 전 후보에 있는 항목만 받는다.
     */
    public AttentionSnapshot snapshot(CurrentUser user) {
        Instant now = clock.instant();
        Map<CardKey, List<AttentionCandidate>> candidates = new EnumMap<>(CardKey.class);
        Set<CardKey> unavailable = EnumSet.noneOf(CardKey.class);
        for (AttentionCandidates source : sources) {
            List<AttentionCandidate> read;
            try {
                read = source.read(user, now);
            } catch (RuntimeException ex) {
                unavailable.addAll(source.cards());
                log.warn("attention source failed userId={} cards={}", user.id(), source.cards());
                continue;
            }
            read.forEach(candidate -> candidates
                    .computeIfAbsent(candidate.card(), card -> new ArrayList<>())
                    .add(candidate));
        }
        List<AttentionCard> cards = judge.judge(
                candidates,
                unavailable,
                controlsOf(user),
                now,
                properties.maxItemsPerCard(),
                properties.continueCount());
        return new AttentionSnapshot(now, candidates, unavailable, cards);
    }

    /** 저장한 제어를 판정에 넘기는 값으로 바꾼다. 숨기기는 상태 지문을, 미루기는 기한을 채운다. */
    private List<AttentionControl> controlsOf(CurrentUser user) {
        return controls.findByUserId(user.id()).stream()
                .map(AttentionService::control)
                .toList();
    }

    private static AttentionControl control(AttentionControlEntry entry) {
        return entry.action() == AttentionAction.HIDE
                ? new AttentionControl(entry.cardKey(), entry.itemKey(), entry.stateKey(), null)
                : new AttentionControl(entry.cardKey(), entry.itemKey(), null, entry.untilAt());
    }

    /** 응답에 실린 항목마다 {@code SHOWN} 사건 하나다. 지금의 출처는 모두 판정할 때 읽은 기록이라 오래되지 않았다. */
    private static List<AttentionEvent> shown(CurrentUser user, AttentionSnapshot snapshot) {
        return snapshot.cards().stream()
                .flatMap(card -> card.items().stream())
                .map(item -> AttentionEvent.of(
                        user.id(),
                        item.itemKey(),
                        item.stateKey(),
                        item.trigger(),
                        item.level(),
                        AttentionEventType.SHOWN,
                        false,
                        snapshot.now()))
                .toList();
    }
}
