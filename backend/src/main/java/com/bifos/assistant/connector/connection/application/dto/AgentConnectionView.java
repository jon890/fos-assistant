package com.bifos.assistant.connector.connection.application.dto;

import com.bifos.assistant.connector.connection.domain.SkippedSkill;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import java.util.List;

/**
 * 에이전트 하나에서 본 내 연결 하나다. 에이전트 상세의 「이 에이전트가 쓰는 연결」 한 줄이다(ADR-083).
 *
 * @param title 카탈로그의 이름. 카탈로그에서 빠진 커넥터는 커넥터 번호다
 * @param connectionStatus 연결의 값이 확인됐는가
 * @param bound 이 에이전트에 붙어 있는가
 * @param status 바인딩의 상태. 붙어 있지 않으면 null
 * @param restartRequired 붙인 뒤 공유 gateway 재시작을 기다린다. 붙어 있지 않으면 거짓
 * @param applyFailed 자동 반영이 실패했다(스펙 DECISION10). 붙어 있지 않으면 거짓
 * @param toolCount 커넥터가 선언한 도구 수. 붙이면 이만큼의 도구 정의가 모든 turn 에 실린다
 * @param skills 붙이면 그 profile 에 설치할 수 있는 스킬 이름. 플러그인 스킬과 에이전트용 스킬을 합친 카탈로그 값이다
 * @param skippedSkills 마지막으로 성공한 설치가 이 에이전트에 설치하지 못한 에이전트용 스킬과 까닭. 붙어 있지 않으면 빈 목록
 */
public record AgentConnectionView(
    String connectorId,
    String title,
    ConnectionStatus connectionStatus,
    boolean bound,
    BindingStatus status,
    boolean restartRequired,
    boolean applyFailed,
    int toolCount,
    List<String> skills,
    List<SkippedSkill> skippedSkills) {

    public AgentConnectionView {
        skills = List.copyOf(skills);
        skippedSkills = List.copyOf(skippedSkills);
    }
}
