package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 승인 줄과 상시 허락 경로의 요청과 응답 모양이다. 계약은 {@code docs/connectors.md} 의 「승인」 이 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorActionDtos {

    /** @param grant 승인하면서 그 도구에 줄 상시 허락의 기간. 주지 않으면 null */
    public record ApproveRequest(GrantPeriod grant) {}

    /** 주인에게만 내는 승인 줄이다. 가린 인자와 결과를 담는다. */
    public record ActionView(
            UUID actionId,
            String connectorId,
            String toolName,
            String title,
            ToolRisk risk,
            ActionStatus status,
            String argsJson,
            String resultText,
            String errorCode,
            Instant createdAt,
            Instant expiresAt,
            boolean grantAllowed) {

        public static ActionView from(ConnectorActionView action) {
            return new ActionView(
                    action.actionId(),
                    action.connectorId(),
                    action.toolName(),
                    action.title(),
                    action.risk(),
                    action.status(),
                    action.argsJson(),
                    action.resultText(),
                    action.errorCode(),
                    action.createdAt(),
                    action.expiresAt(),
                    action.grantAllowed());
        }
    }

    public record GrantView(Long grantId, String connectorId, String toolName, String title, Instant expiresAt) {

        public static GrantView from(ConnectorGrantView grant) {
            return new GrantView(
                    grant.grantId(), grant.connectorId(), grant.toolName(), grant.title(), grant.expiresAt());
        }
    }
}
