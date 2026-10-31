package com.bifos.assistant.followup.application.dto;

/**
 * 먼저 살펴보기의 결과 블록이 낸 제안 항목 하나다. 서버가 항목을 검사한 값이고, 사용자는 실행 줄에서 정하므로 여기에 없다.
 *
 * @param itemKey 모델이 준 항목 식별자. 비어 있으면 결과 블록 안의 순번을 쓴다
 * @param title 할 일 제목. 앞뒤 공백을 지운 글자 수가 {@code FollowUpService.TITLE_MAX} 이하여야 한다
 * @param detailJson 모델이 낸 자유 JSON 본문. 구조는 보지 않고 크기만 검사한다. 비어 있을 수 있다
 */
public record SuggestedItem(String itemKey, String title, String detailJson) {
}
