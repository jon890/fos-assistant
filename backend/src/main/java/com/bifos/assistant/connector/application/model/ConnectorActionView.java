package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.hermes.ToolDetailRedactor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 주인에게 보이는 승인 줄이다(ADR-050). 인자와 결과를 담으므로 주인 밖에는 내지 않는다.
 *
 * @param actionId 승인 요청 번호. 공개 식별자다
 * @param toolName 원래 도구 이름. 확인하지 못한 호출은 null
 * @param title 사람에게 보일 이름. 카탈로그의 선언에 이름이 없거나 카탈로그를 읽지 못했으면 고정 문구다. 도구의 원래
 *     이름은 내부 값이라 여기 싣지 않는다
 * @param argsJson 승인할 인자. 비밀처럼 보이는 값과 식별자는 가린 글이다. 실행은 저장한 원문으로 한다
 * @param grantAllowed 승인하면서 상시 허락을 줄 수 있는 도구인가. 승인 줄에 저장하지 않고 줄을 읽을 때의 카탈로그로
 *     본다(ADR-062)
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

    /** 선언한 이름이 없는 도구를 사용자에게 부르는 말이다. */
    public static final String UNNAMED_TITLE = "이름 없는 동작";

    /**
     * @param declared 카탈로그가 선언한 그 도구의 정책. 선언이 없거나 카탈로그를 읽지 못했으면 빈 값
     * @param grantable 지금 카탈로그로 볼 때 그 도구에 상시 허락을 줄 수 있는가
     */
    public static ConnectorActionView from(ConnectorAction action, Optional<ToolPolicy> declared, boolean grantable) {
        String title = declared.map(ToolPolicy::title)
                .filter(declaredTitle -> !declaredTitle.isBlank())
                .orElse(UNNAMED_TITLE);
        return new ConnectorActionView(
                action.publicId(),
                action.connectorId(),
                action.toolName(),
                title,
                action.risk(),
                action.status(),
                ToolDetailRedactor.redactArguments(action.argsJson()),
                action.resultText(),
                action.errorCode(),
                action.createdAt(),
                action.expiresAt(),
                action.grantAllowed() && grantable);
    }
}
