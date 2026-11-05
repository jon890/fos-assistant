package com.bifos.assistant.proactive.check.domain;

/**
 * 보고가 「새로 알릴 것」 으로 알린 발견 하나의 되풀이 판정 재료다. 주제 키가 있는 발견만 남긴다. 다음 살펴보기가 같은 주제 키와 원문
 * 주소를 이미 알렸는지 보고, 입력의 「최근 보고에서 알린 발견」 에 싣는다.
 *
 * @param title 모델이 쓴 제목이다. 입력에 실을 때 {@code <external-data>} 로 감싼다
 */
public record ReportedFinding(String topicKey, String sourceUrl, String title) {
}
