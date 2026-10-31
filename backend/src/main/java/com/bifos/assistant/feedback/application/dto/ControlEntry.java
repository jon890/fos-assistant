package com.bifos.assistant.feedback.application.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * 지금 화면의 제어와 노출 사건 한 줄이다. 일반 사건({@link FeedbackEntry})에 카드 범위, 미루기 끝, 재전송 합치기 열쇠를 더한다.
 *
 * <p>제어 사건(숨김, 미루기, 복원)은 {@code cardKey} 가 있다. 숨김이면 {@code entry.subjectVersion} 이 숨긴 상태의 지문이고,
 * 미루기면 {@code untilAt} 이 끝나는 시각이다.
 *
 * @param cardKey 제어가 걸린 카드 열쇠. 카드와 무관한 사건이면 null
 * @param untilAt 미루기가 끝나는 시각. 미루기가 아니면 null
 * @param eventKey 같은 사용자의 같은 열쇠를 한 번만 남기는 열쇠. 합치지 않으면 null
 */
public record ControlEntry(FeedbackEntry entry, String cardKey, Instant untilAt, String eventKey) {

    public ControlEntry {
        Objects.requireNonNull(entry, "entry");
    }
}
