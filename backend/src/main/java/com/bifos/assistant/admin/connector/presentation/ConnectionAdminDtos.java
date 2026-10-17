package com.bifos.assistant.admin.connector.presentation;

import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.AgentConnectionView;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 연결 경로가 주고받는 모양이다. 사용자 경로와 계약이 따로 바뀔 수 있어 겹치는 record 는 {@code ConnectionDtos} 에서 복사해 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectionAdminDtos {

    /**
     * 관리자가 보는 바인딩 한 줄이다. 다른 사용자의 칸 값과 비밀 앞부분은 담지 않는다.
     *
     * @param restartRequiredSince 반영 완료 요청이 그대로 돌려보낼 재시작 대기 시작 시각
     */
    public record AdminConnectionView(
        String connectorId,
        Long userId,
        String displayName,
        String agentCode,
        String status,
        boolean restartRequired,
        Instant restartRequiredSince,
        int undeclaredTools) {
        static AdminConnectionView from(AdminConnectionSnapshot value) {
            return new AdminConnectionView(
                value.connectorId(),
                value.userId(),
                value.displayName(),
                value.agentCode(),
                value.status().name(),
                value.restartRequired(),
                value.restartRequiredSince(),
                value.undeclaredTools());
        }
    }

    /**
     * 관리자 반영 완료 요청이다.
     *
     * @param restartRequiredSince 관리자 목록에서 본 그 바인딩의 재시작 대기 시작 시각. 대기가 없었으면 null
     */
    public record ConfirmRequest(Instant restartRequiredSince) {
    }

    /**
     * 에이전트 하나에서 본 연결 하나다. 상태는 enum 이름 그대로 내고, 붙어 있지 않으면 {@code status} 가 null 이다.
     *
     * @param toolCount 커넥터가 선언한 도구 수
     * @param skills 붙이면 그 profile 에 설치되는 커넥터 스킬 이름
     */
    public record AgentConnectionResponse(
        String connectorId,
        String title,
        String connectionStatus,
        boolean bound,
        String status,
        boolean restartRequired,
        int toolCount,
        List<String> skills) {
        static AgentConnectionResponse from(AgentConnectionView value) {
            return new AgentConnectionResponse(
                value.connectorId(),
                value.title(),
                value.connectionStatus().name(),
                value.bound(),
                value.status() == null ? null : value.status().name(),
                value.restartRequired(),
                value.toolCount(),
                value.skills());
        }
    }
}
