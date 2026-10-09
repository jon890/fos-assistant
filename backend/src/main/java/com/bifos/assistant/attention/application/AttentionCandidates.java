package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.util.Sha256;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 원래 기록 하나를 읽어 판정 후보를 낸다.
 *
 * <p>구현마다 따로 읽는다. 한 구현이 예외를 던지면 그 구현의 {@link #cards()} 만 {@code UNAVAILABLE} 이 되고 다른 카드는 그대로
 * 나간다. 읽지 못한 기록을 추정해 후보로 넣지 않는다.
 */
public interface AttentionCandidates {

    /** 이 구현이 후보를 내는 카드다. */
    Set<CardKey> cards();

    /** 요청자의 원래 기록을 읽어 후보를 낸다. 다른 패키지의 기록을 고치지 않는다. */
    List<AttentionCandidate> read(CurrentUser user, Instant now);

    /** 상태 지문이다. 재료는 {@code docs/features/attention.md} 「후보와 trigger」 표의 「{@code stateKey} 의 재료」 칸이다. */
    static String stateKey(AttentionTrigger trigger, String material) {
        return Sha256.hex16(trigger.name() + "|" + material);
    }
}
