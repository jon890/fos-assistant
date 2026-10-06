package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.presentation.ConnectionDtos.AgentConnectionResponse;
import com.bifos.assistant.connector.presentation.ConnectionDtos.ConfirmRequest;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자가 공유 gateway 를 재시작한 뒤 바인딩마다 누르는 반영 완료다(ADR-083).
 *
 * <p>관리자는 남의 에이전트에 연결을 붙이거나 떼지 못하고 이 경로만 쓴다. 응답에는 주인의 칸 값과 비밀 앞부분이 없다.
 */
@RestController
@RequestMapping("/api/v1/admin/agents")
@RequiredArgsConstructor
public class AdminAgentConnectionController {
    private final ConnectorBindingService bindings;
    private final CurrentUserProvider currentUser;

    /** 본문은 관리자 목록에서 본 그 바인딩의 재시작 대기 시작 시각이다. 그 뒤에 다시 설치됐으면 거절한다. */
    @PostMapping("/{code}/connections/{connectorId}/confirm")
    public AgentConnectionResponse confirm(
            @PathVariable String code, @PathVariable String connectorId, @RequestBody ConfirmRequest request) {
        return AgentConnectionResponse.from(
                bindings.confirmApplied(currentUser.requireAdmin(), code, connectorId, request.restartRequiredSince()));
    }
}
