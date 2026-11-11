package com.bifos.assistant.connector.handoff.application.dto;

import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import java.time.Instant;
import java.util.UUID;

/**
 * 대화의 화면 넘기기 카드 하나다. 카드는 이 값으로만 그린다. 모델이 쓴 글은 들어 있지 않다.
 *
 * @param handoffId 그 호출의 판정 줄의 공개 번호
 * @param connectorTitle 카탈로그의 플러그인 이름. 읽지 못하면 플러그인 번호
 * @param tab 플러그인이 남긴 「내 브라우저」 탭의 CDP target id
 */
public record HandoffView(
    UUID handoffId, String connectorId, String connectorTitle, HandoffNeed need, String tab, Instant createdAt) {
}
