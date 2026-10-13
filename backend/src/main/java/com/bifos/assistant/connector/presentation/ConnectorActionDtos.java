package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ContentDraftActionSummary;
import com.bifos.assistant.connector.application.model.ConnectorConversationGrantView;
import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.application.model.FinancialActionSummary;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 승인 줄과 상시 허락 경로의 요청과 응답 모양이다. 계약은 {@code docs/features/connector-policy.md} 의 「커넥터 승인」 이 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorActionDtos {

    /**
     * @param grant 승인하면서 그 도구에 줄 상시 허락의 기간. 주지 않으면 null
     * @param conversationGrant 승인하면서 그 도구를 이 대화에서 계속 허용하는가. {@code grant} 와 함께 줄 수 없다. 없으면 거짓이다
     */
    public record ApproveRequest(GrantPeriod grant, Boolean conversationGrant) {
    }

    /**
     * 주인에게만 내는 승인 줄이다. 가린 인자와 결과를 담는다.
     *
     * @param contentDraft 원고 저장 job 이 연결된 줄의 요약. 일반 줄은 null
     * @param financial 금융 줄의 승인 카드 요약. 금융 줄이 아니면 null
     */
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
        boolean grantAllowed,
        boolean hiddenArgs,
        Map<String, String> argLabels,
        boolean conversationGrantAllowed,
        ContentDraftActionSummary contentDraft,
        FinancialActionSummary financial) {

        public static ActionView from(ConnectorActionView action) {
            return from(action, null);
        }


        public static ActionView from(ConnectorActionView action, ContentDraftActionSummary contentDraft) {
            return from(action, contentDraft, null);
        }


        public static ActionView from(
            ConnectorActionView action, ContentDraftActionSummary contentDraft, FinancialActionSummary financial) {
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
                action.grantAllowed(),
                action.hiddenArgs(),
                action.argLabels(),
                action.conversationGrantAllowed(),
                contentDraft,
                financial);
        }
    }

    public record GrantView(Long grantId, String connectorId, String toolName, String title, Instant expiresAt) {

        public static GrantView from(ConnectorGrantView grant) {
            return new GrantView(
                grant.grantId(), grant.connectorId(), grant.toolName(), grant.title(), grant.expiresAt());
        }
    }

    /** @param agentName 대화의 에이전트와 다른 에이전트에 준 허락이면 그 이름. 아니면 null */
    public record ConversationGrantView(Long grantId, String title, Instant expiresAt, String agentName) {

        public static ConversationGrantView from(ConnectorConversationGrantView grant) {
            return new ConversationGrantView(grant.grantId(), grant.title(), grant.expiresAt(), grant.agentName());
        }
    }
}
