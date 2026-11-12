package com.bifos.assistant.agent.application.dto;

import com.bifos.assistant.agent.domain.Agent;

/**
 * 화면이 이름표 하나를 그리는 데 쓰는 에이전트 값이다. 맡긴 줄, 결과 도착 줄, 승인 카드가 같은 모양으로 싣는다.
 *
 * <p>지운 에이전트와 행이 없는 에이전트는 네 칸이 모두 null 이고 화면이 「지운 에이전트」 로 그린다. 대화 줄의 에이전트 칸과 같은
 * 규칙이다.
 *
 * @param code 에이전트 코드. 자동 색을 정한다
 * @param name 이름
 * @param icon 아이콘. 정하지 않았으면 null
 * @param color 색 이름. 정하지 않았으면 null
 */
public record AgentLabel(String code, String name, String icon, String color) {


    /** 지운 에이전트나 행이 없는 에이전트의 이름표다. */
    public static final AgentLabel DELETED = new AgentLabel(null, null, null, null);

    /** @param agent 행이 없으면 null */
    public static AgentLabel of(Agent agent) {
        if (agent == null || agent.isDeleted()) {
            return DELETED;
        }
        return new AgentLabel(
            agent.code(),
            agent.name(),
            agent.icon(),
            agent.color() == null ? null : agent.color().name());
    }
}
