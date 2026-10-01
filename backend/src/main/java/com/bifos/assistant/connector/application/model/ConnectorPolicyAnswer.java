package com.bifos.assistant.connector.application.model;

import java.util.UUID;

/**
 * 도구 호출 판정이 hook 에 돌려줄 답이다(ADR-048).
 *
 * @param allowed hook 이 그 호출을 통과시켜도 되는가
 * @param message 막을 때 모델에게 보일 글. 통과이면 빈 글이다. 내부 이름과 예외 본문을 넣지 않는다
 * @param actionId 승인 요청 번호. 승인 요청을 만들지 않았으면 null
 */
public record ConnectorPolicyAnswer(boolean allowed, String message, UUID actionId) {}
