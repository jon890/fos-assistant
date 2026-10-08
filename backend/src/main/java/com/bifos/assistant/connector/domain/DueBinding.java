package com.bifos.assistant.connector.domain;

/**
 * 반영 예정 확인이 잠글 행의 번호들이다(ADR-20261007 / connector-live-reload).
 *
 * <p>트랜잭션 밖에서 읽고 바인딩마다 잠근 뒤 다시 읽는다. 엔티티를 들고 다니면 잠그기 전의 값을 보게 되므로 번호만 둔다.
 *
 * @param bindingId 바인딩 번호
 * @param agentId 바인딩의 에이전트 번호
 * @param userId 바인딩의 연결 사용자 번호. 붙이기 규칙으로 에이전트 주인과 같다
 */
public record DueBinding(Long bindingId, Long agentId, Long userId) {}
