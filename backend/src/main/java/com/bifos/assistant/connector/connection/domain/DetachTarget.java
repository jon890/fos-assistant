package com.bifos.assistant.connector.connection.domain;

/**
 * 사용자 정리가 profile 에서 뗄 바인딩 하나다(ADR-20261101 / user-purge).
 *
 * <p>엔티티를 읽지 않고 뗄 때 필요한 값만 둔다. 정리는 연결의 칸 값을 풀 필요가 없고, 풀지 못하는 줄이 있어도 정리가 멈추지 않아야 한다.
 *
 * @param bindingId 바인딩 번호
 * @param profile 바인딩한 에이전트의 Hermes profile
 * @param connectorId 연결의 커넥터 번호
 */
public record DetachTarget(Long bindingId, String profile, String connectorId) {
}
