package com.bifos.assistant.agent.admin.application.model;

import com.bifos.assistant.agent.domain.type.AgentVisibility;

/**
 * 관리자가 에이전트의 접근 범위와 Hermes 주소를 고칠 때 넘기는 값이다.
 *
 * <p>값의 모양 검증은 요청 DTO 가 끝낸다. 여기에는 검증 애너테이션을 달지 않는다.
 *
 * @param ownerEmail 새 주인의 메일 주소. 비어 있으면 지금 주인이 남는다
 * @param apiBaseUrl 새 Hermes API 주소. 비어 있으면 지금 값을 그대로 둔다
 * @param proactiveCheckWritesAllowed 「먼저 살펴보기에 쓰기 도구 허용」 의 새 값. 비어 있으면 지금 값을 그대로 둔다(ADR-082)
 */
public record AgentUpdateCommand(
        Boolean enabled,
        AgentVisibility visibility,
        String ownerEmail,
        String apiBaseUrl,
        Boolean proactiveCheckWritesAllowed) {}
