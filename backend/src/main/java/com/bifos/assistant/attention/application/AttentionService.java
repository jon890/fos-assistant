package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.domain.type.CardKey;
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
 * 요청자의 원래 기록을 읽어 지금 화면의 카드 넷을 계산한다. 판정 결과를 저장하지 않는다(ADR-072).
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
    private final Clock clock;

    /** 카드 넷과 건수를 계산한다. 읽지 못한 기록이 든 카드만 {@code UNAVAILABLE} 이다. */
    public AttentionView view(CurrentUser user) {
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
        return AttentionView.of(
                now,
                judge.judge(
                        candidates,
                        unavailable,
                        List.of(),
                        now,
                        properties.maxItemsPerCard(),
                        properties.continueCount()));
    }

    /** {@link #view} 와 같은 계산의 맨 위 건수다. 사이드바의 수가 카드 배지의 합과 늘 같다. */
    public int summary(CurrentUser user) {
        return view(user).nowCount();
    }
}
