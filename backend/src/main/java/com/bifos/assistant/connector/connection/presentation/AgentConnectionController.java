package com.bifos.assistant.connector.connection.presentation;

import com.bifos.assistant.agent.application.PrimaryAgents;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.presentation.ConnectionDtos.AgentConnectionResponse;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionsView;
import com.bifos.assistant.connector.connection.presentation.ConnectionDtos.AgentConnectionsResponse;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트 상세의 「이 에이전트가 쓰는 연결」 이다. 내 연결을 내 에이전트에 붙이고 뗀다(ADR-083).
 *
 * <p>세 경로 모두 그 에이전트의 주인만 쓴다. 누구의 연결인지는 로그인에서만 정하고 요청 본문을 받지 않는다. 읽을 수 없는
 * 에이전트는 없는 에이전트와 같은 응답이고, 읽을 수 있어도 주인이 아니면 관리자도 거절된다. 관리자는 반영 완료만 누른다.
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentConnectionController {
    private final ConnectorBindingService bindings;
    private final PrimaryAgents primaryAgents;
    private final CurrentUserProvider currentUser;


    @GetMapping("/{code}/connections")
    public AgentConnectionsResponse list(@PathVariable String code) {
        CurrentUser user = currentUser.require();
        AgentConnectionsView view = bindings.listForAgent(user, code);
        return AgentConnectionsResponse.from(
            isPrimary(user, code) ? new AgentConnectionsView(view.connections(), AgentConnectionsView.PRIMARY_AGENT) : view);
    }


    /** 이미 붙어 있으면 지금 상태를 돌려준다. 붙인 바인딩은 관리자 반영 완료 전까지 재시작 대기다. 기본 비서에는 붙이지 않는다. */
    @PutMapping("/{code}/connections/{connectorId}")
    public AgentConnectionResponse bind(@PathVariable String code, @PathVariable String connectorId) {
        CurrentUser user = currentUser.require();
        if (isPrimary(user, code)) {
            throw new ApiException(ErrorCode.PRIMARY_AGENT_NO_CONNECTIONS, "connections are attached to specialist agents only");
        }
        return AgentConnectionResponse.from(bindings.bind(user, code, connectorId));
    }


    @DeleteMapping("/{code}/connections/{connectorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable String code, @PathVariable String connectorId) {
        bindings.unbind(currentUser.require(), code, connectorId);
    }


    /**
     * 기본 비서에는 화면에서 연결을 붙이지 않는다(docs/features/assistant-hub/spec.md 의 REQ9, DECISION7). {@code bind} 자체는 막지 않는다.
     * 전문 에이전트 만들기가 실패해 비서에서 뗀 연결을 되돌려 붙일 때 쓰기 때문이다. 떼기는 막지 않는다.
     */
    private boolean isPrimary(CurrentUser user, String code) {
        return primaryAgents.of(user).map(Agent::code).filter(code::equals).isPresent();
    }
}
