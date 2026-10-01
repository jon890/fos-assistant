package com.bifos.assistant.connector.application.model;

import java.util.UUID;

/**
 * 승인 줄이 생겼거나 상태가 바뀌었다는 사건이다(ADR-048).
 *
 * <p>줄을 커밋한 뒤에 낸다. 받은 쪽이 읽었을 때 줄이 있어야 한다. 대화 없는 실행이 만든 줄은 전할 곳이 없어 내지
 * 않는다.
 *
 * @param conversationId 그 줄의 대화 번호
 * @param actionId 승인 요청 번호
 */
public record ConnectorActionChanged(Long conversationId, UUID actionId) {}
