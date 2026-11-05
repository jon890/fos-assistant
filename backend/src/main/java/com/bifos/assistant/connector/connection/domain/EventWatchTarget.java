package com.bifos.assistant.connector.connection.domain;

import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;

/**
 * 사건 감지를 켠 바인딩 하나를 확인할 때 쓰는 값이다(ADR-20261105 / event-watch). 엔티티를 들고 다니지 않게 필요한 값만 둔다.
 *
 * @param userId 연결의 사용자. 붙이기 규칙으로 에이전트 주인과 같다
 * @param profile 에이전트의 Hermes profile. 목록 조회는 이 profile 의 연결 값으로만 한다(rule-47)
 * @param state 저장된 상태 JSON 글
 */
public record EventWatchTarget(
    Long bindingId,
    Long agentId,
    String agentCode,
    Long userId,
    String profile,
    String connectorId,
    BindingStatus bindingStatus,
    boolean desiredEnabled,
    ConnectionStatus connectionStatus,
    String state) {

    /** 바인딩과 연결이 모두 쓸 수 있는 상태인가. */
    public boolean usable() {
        return bindingStatus == BindingStatus.READY && desiredEnabled && connectionStatus == ConnectionStatus.READY;
    }
}
