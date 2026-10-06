package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.util.List;

/**
 * 에이전트 하나에서 본 내 연결 하나다. 에이전트 상세의 「이 에이전트가 쓰는 연결」 한 줄이다(ADR-083).
 *
 * @param title 카탈로그의 이름. 카탈로그에서 빠진 커넥터는 커넥터 번호다
 * @param connectionStatus 연결의 값이 확인됐는가
 * @param bound 이 에이전트에 붙어 있는가
 * @param status 바인딩의 상태. 붙어 있지 않으면 null
 * @param restartRequired 붙인 뒤 공유 gateway 재시작을 기다린다. 붙어 있지 않으면 거짓
 * @param toolCount 커넥터가 선언한 도구 수. 붙이면 이만큼의 도구 정의가 모든 turn 에 실린다
 * @param skills 붙이면 그 profile 에 설치되는 커넥터 스킬 이름
 */
public record AgentConnectionView(
        String connectorId,
        String title,
        ConnectionStatus connectionStatus,
        boolean bound,
        BindingStatus status,
        boolean restartRequired,
        int toolCount,
        List<String> skills) {

    public AgentConnectionView {
        skills = List.copyOf(skills);
    }
}
