package com.bifos.assistant.followup.application.dto;

import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import java.util.UUID;

/**
 * 점검 대화의 최근 제안 줄 하나다. 다음 살펴보기의 입력에 싣는다.
 *
 * @param publicId 반응 사건 열쇠({@code follow_up:<공개 식별자>})를 만든다
 * @param title 모델이 쓴 제목이다. 입력에 실을 때 {@code <external-data>} 로 감싼다
 */
public record RecentSuggestion(UUID publicId, String title, FollowUpStatus status) {
}
