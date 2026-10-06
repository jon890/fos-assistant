package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.presentation.ConnectionDtos.AgentConnectionResponse;
import com.bifos.assistant.connector.presentation.ConnectionDtos.AgentConnectionsResponse;
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
    private final CurrentUserProvider currentUser;

    @GetMapping("/{code}/connections")
    public AgentConnectionsResponse list(@PathVariable String code) {
        return AgentConnectionsResponse.from(bindings.listForAgent(currentUser.require(), code));
    }

    /** 이미 붙어 있으면 지금 상태를 돌려준다. 붙인 바인딩은 관리자 반영 완료 전까지 재시작 대기다. */
    @PutMapping("/{code}/connections/{connectorId}")
    public AgentConnectionResponse bind(@PathVariable String code, @PathVariable String connectorId) {
        return AgentConnectionResponse.from(bindings.bind(currentUser.require(), code, connectorId));
    }

    @DeleteMapping("/{code}/connections/{connectorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable String code, @PathVariable String connectorId) {
        bindings.unbind(currentUser.require(), code, connectorId);
    }
}
