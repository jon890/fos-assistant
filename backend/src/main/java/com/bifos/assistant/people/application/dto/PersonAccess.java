package com.bifos.assistant.people.application.dto;

import com.bifos.assistant.people.domain.AllowedPerson;
import java.time.Instant;

/**
 * 허용 목록의 사람 하나와, 그 사람이 들어온 적이 있는지({@code app_user} 가 있는지)다.
 *
 * @param evaluation 배포 뒤 확인에 쓰는 평가 전용 사용자인가. 관리자 사용자 목록이 「평가용」 으로 표시한다
 */
public record PersonAccess(AllowedPerson person, boolean joined, Instant lastConversationAt, boolean evaluation) {
}
