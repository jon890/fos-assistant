package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.dto.AttentionReaction;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.shared.util.Sha256;
import java.util.Locale;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 지금 화면의 사건을 반응 기록의 칸으로 옮기고 읽는 규칙이다. 칸의 쓰임은 {@code backend/docs/data-schema.md} 의
 * 「decision_feedback_event」 가 갖는다.
 *
 * <ul>
 * <li>{@code subject_key} 는 항목 열쇠, {@code subject_version} 은 상태 지문이다</li>
 * <li>{@code reason_code} 는 {@code <계기>@<판정>} 이다. 이관한 옛 사건은 판정을 옮기지 않아 {@code <계기>} 뿐이다</li>
 * <li>{@code event_key} 는 종류, 항목, 상태, 판정을 해시한 값이라 같은 노출을 한 줄로 합친다</li>
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class AttentionReactionCodec {

    private static final String LEVEL_SEPARATOR = "@";

    /** 제어 사건의 요청 키를 합칠 때 붙이는 머리다. */
    private static final String CONTROL_PREFIX = "control:";


    static FeedbackEventType feedbackTypeOf(AttentionEventType type) {
        return switch (type) {
            case SHOWN -> FeedbackEventType.SHOWN;
            case OPENED -> FeedbackEventType.OPENED;
            case ACTED -> FeedbackEventType.ACTED;
            case HIDDEN -> FeedbackEventType.DISMISSED;
            case SNOOZED -> FeedbackEventType.POSTPONED;
        };
    }


    static String reasonOf(AttentionReaction reaction) {
        return reaction.trigger().name() + LEVEL_SEPARATOR + reaction.level().name();
    }


    /** 같은 항목, 상태, 판정, 종류의 노출을 한 줄로 합치는 열쇠다. 칸 길이(120)를 넘지 않게 해시한다. */
    static String eventKeyOf(AttentionReaction reaction) {
        return reaction.type().name().toLowerCase(Locale.ROOT) + ":"
            + sha256(reaction.itemKey() + "|" + reaction.stateKey() + "|" + reaction.level().name());
    }


    /**
     * 제어 요청의 재전송을 한 줄로 합치는 열쇠다. 요청 키가 같아도 동작, 카드, 항목이 다르면 다른 열쇠다.
     *
     * @return 요청 키가 없으면 null
     */
    static String controlEventKey(String requestKey, FeedbackEventType type, String card, String itemKey) {
        if (requestKey == null) {
            return null;
        }
        return CONTROL_PREFIX + sha256(type.name() + "|" + card + "|" + itemKey + "|" + requestKey);
    }


    /** {@code reason_code} 에서 계기를 읽는다. 모르는 계기면 비어 있다. */
    static Optional<AttentionTrigger> triggerOf(String reasonCode) {
        if (reasonCode == null) {
            return Optional.empty();
        }
        int at = reasonCode.indexOf(LEVEL_SEPARATOR);
        String name = at < 0 ? reasonCode : reasonCode.substring(0, at);
        try {
            return Optional.of(AttentionTrigger.valueOf(name));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }


    /** {@code reason_code} 에서 판정을 읽는다. 이관한 옛 사건처럼 판정이 없으면 비어 있다. */
    static Optional<AttentionLevel> levelOf(String reasonCode) {
        if (reasonCode == null) {
            return Optional.empty();
        }
        int at = reasonCode.indexOf(LEVEL_SEPARATOR);
        if (at < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(AttentionLevel.valueOf(reasonCode.substring(at + 1)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }


    private static String sha256(String value) {
        return Sha256.hex(value);
    }
}
