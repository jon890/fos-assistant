package com.bifos.assistant.attention.application.dto;

import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import java.time.Instant;
import java.util.Objects;

/**
 * 지금 화면에서 항목이 보였거나 열렸거나 동작한 사건 하나다. 반응 기록에 남기기 전의 값이다.
 *
 * @param stateKey 그때 항목의 상태 지문
 * @param trigger 그 항목이 후보가 된 계기
 * @param level 그때의 판정. 억제된 항목에 한 일이면 {@code SUPPRESSED}
 * @param type {@code SHOWN}, {@code OPENED}, {@code ACTED} 중 하나
 */
public record AttentionReaction(
    Long userId,
    String itemKey,
    String stateKey,
    AttentionTrigger trigger,
    AttentionLevel level,
    AttentionEventType type,
    Instant at) {

    public AttentionReaction {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(stateKey, "stateKey");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");
    }
}
