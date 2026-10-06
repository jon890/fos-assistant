package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.type.BindingStatus;

/**
 * 연결이 붙은 에이전트 하나와 그 바인딩의 상태다. 연결 화면이 「이 연결을 쓰는 에이전트」 로 보인다.
 *
 * @param restartRequired 그 에이전트의 profile 이 공유 gateway 재시작을 기다린다
 */
public record BoundAgentSummary(String agentCode, String agentName, BindingStatus status, boolean restartRequired) {

    public static BoundAgentSummary from(ConnectorBinding binding) {
        return new BoundAgentSummary(
                binding.agent().code(), binding.agent().name(), binding.status(), binding.restartRequired());
    }
}
