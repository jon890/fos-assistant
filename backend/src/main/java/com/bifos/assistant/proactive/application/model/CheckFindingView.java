package com.bifos.assistant.proactive.application.model;

/**
 * 점검 대화에서 「새로 알릴 것」 으로 그린 발견 하나와 그 지금 반응이다.
 *
 * @param executionId 그 살펴보기의 루트 실행. 답 메시지의 실행 번호와 같아 화면이 답 아래 자리를 정하는 데 쓴다
 * @param topicKey 분야 지침이 정한 주제 키. 없으면 null
 * @param reaction 그 발견의 마지막 사용자 반응. 없으면 null
 */
public record CheckFindingView(
        Long id,
        Long checkId,
        Long executionId,
        String area,
        String topicKey,
        String title,
        FindingReaction reaction) {}
