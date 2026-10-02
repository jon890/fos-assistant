package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;

/**
 * 관리자가 에이전트를 등록할 때 넘기는 값이다.
 *
 * <p>값의 모양 검증은 요청 DTO 가 끝낸다. 여기에는 검증 애너테이션을 달지 않는다.
 *
 * @param ownerEmail 주인의 메일 주소. 비어 있으면 그룹 공개 에이전트는 주인 없이 둔다
 * @param flow 흐름 이름. 비어 있으면 흐름을 두지 않는다
 */
public record AgentCreateCommand(
        String code,
        String name,
        String hermesProfile,
        String apiBaseUrl,
        CostMode costMode,
        CredentialScope credentialScope,
        AgentVisibility visibility,
        String ownerEmail,
        String flow) {}
