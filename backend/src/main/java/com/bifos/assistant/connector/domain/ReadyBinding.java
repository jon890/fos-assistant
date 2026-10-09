package com.bifos.assistant.connector.domain;

/**
 * 정의 어긋남 점검이 설치 상태를 읽고 잠글 {@code READY} 바인딩이다(ADR-20261009 / connector-install-drift).
 *
 * <p>트랜잭션 밖에서 읽고, 설치 상태도 트랜잭션 밖에서 읽는다. 어긋났을 때만 바인딩마다 잠근 뒤 다시 읽는다. 엔티티를 들고 다니면
 * 잠그기 전의 값을 보게 되므로 번호와 설치 상태를 읽는 데 필요한 값만 둔다.
 *
 * @param bindingId 바인딩 번호
 * @param agentId 바인딩의 에이전트 번호
 * @param userId 바인딩의 연결 사용자 번호. 붙이기 규칙으로 에이전트 주인과 같다
 * @param profile 에이전트의 Hermes profile
 * @param connectorId 연결의 커넥터 id
 * @param legacy 옛 커넥터 에이전트의 바인딩인가. 설치 판정이 바인딩 방식을 보지 않는다
 */
public record ReadyBinding(
        Long bindingId, Long agentId, Long userId, String profile, String connectorId, boolean legacy) {}
