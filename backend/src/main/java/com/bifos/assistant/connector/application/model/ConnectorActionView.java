package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import java.time.Instant;
import java.util.UUID;

/**
 * 주인에게 보이는 승인 줄이다(ADR-050). 인자 원문과 결과를 담으므로 주인 밖에는 내지 않는다.
 *
 * @param actionId 승인 요청 번호. 공개 식별자다
 * @param toolName 원래 도구 이름. 확인하지 못한 호출은 null
 * @param title 사람에게 보일 이름. 카탈로그의 선언에 없으면 원래 이름, 그것도 없으면 등록 이름
 * @param grantAllowed 승인하면서 상시 허락을 줄 수 있는 도구인가
 */
public record ConnectorActionView(
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

    /** @param declaredTitle 카탈로그가 선언한 이름. 없거나 읽지 못했으면 null */
    public static ConnectorActionView from(ConnectorAction action, String declaredTitle) {
        String title = declaredTitle;
        if (title == null) {
            title = action.toolName() == null ? action.hermesTool() : action.toolName();
        }
        return new ConnectorActionView(
                action.publicId(),
                action.connectorId(),
                action.toolName(),
                title,
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
