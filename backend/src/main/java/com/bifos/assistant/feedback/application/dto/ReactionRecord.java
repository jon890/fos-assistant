package com.bifos.assistant.feedback.application.dto;

import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import java.time.Instant;

/**
 * 지금 화면의 제어와 노출 사건을 읽은 값이다. 다른 기능이 반응 기록의 엔티티를 직접 만지지 않게 하려고 칸을 줄여 낸다.
 *
 * @param stateKey 사건 때 항목의 상태 지문({@code subject_version}). 숨김이면 숨긴 상태다
 * @param reasonCode 정형 까닭. 제어 사건은 단추 이름이고, 노출과 열기는 후보의 계기와 판정이다
 * @param cardKey 제어가 걸린 카드 열쇠. 카드와 무관한 사건이면 null
 * @param untilAt 미루기가 끝나는 시각. 미루기가 아니면 null
 */
public record ReactionRecord(
    Long id,
    Long userId,
    String subjectKey,
    String stateKey,
    FeedbackEventType type,
    String reasonCode,
    String cardKey,
    Instant untilAt,
    Instant occurredAt) {
}
