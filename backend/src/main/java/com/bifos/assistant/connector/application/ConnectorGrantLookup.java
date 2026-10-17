package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorConversationGrantRepository;
import com.bifos.assistant.connector.infra.ConnectorToolGrantRepository;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 도구 호출 판정이 보는 두 허락을 찾는다. 원래 도구 이름을 확인하지 못한 호출은 어느 허락도 없는 것이다.
 *
 * <p>허락을 볼지는 선언이 정한다. 그 판단은 {@code ToolPolicyDecision.decide} 가 한다. 결과를 모르는 쓰기를 같은 재시도 식별자로
 * 다시 보내는 호출은 두 허락이 모두 없는 것으로 본다(ADR-20261013 unknown-retry-key).
 */
@Component
@RequiredArgsConstructor
class ConnectorGrantLookup {

    private final ConnectorToolGrantRepository grants;
    private final ConnectorConversationGrantRepository conversationGrants;
    private final ConnectorUnknownResends unknownResends;


    /** 결과를 모르는 쓰기를 같은 재시도 식별자로 다시 보내는 호출인가. 참이면 판정에 두 허락을 넘기지 않는다. */
    boolean resendsUnknown(ConnectorConnection connection, String hermesTool, String argsJson) {
        return unknownResends.resends(connection, hermesTool, argsJson);
    }


    /** 그 사용자가 그 커넥터의 그 도구에 준 유효한 상시 허락이 있는가(ADR-050). */
    boolean standing(ConnectorConnection connection, String confirmedTool, Instant now) {
        return confirmedTool != null
            && grants.existsActive(connection.userId(), connection.connectorId(), confirmedTool, now);
    }


    /**
     * 그 실행의 대화와 에이전트에 그 도구의 유효한 대화 허락이 있는가(ADR-20261012 / conversation-grant).
     *
     * <p>대화나 에이전트가 없는 실행은 없는 것이다. 위임 자식은 부모 대화에서 돌지만 에이전트가 다르면 통하지 않는다.
     */
    boolean inConversation(AgentExecution origin, ConnectorConnection connection, String confirmedTool, Instant now) {
        return confirmedTool != null
            && origin.conversationId() != null
            && origin.agentId() != null
            && conversationGrants.existsActive(
                connection.userId(),
                origin.conversationId(),
                origin.agentId(),
                connection.connectorId(),
                confirmedTool,
                now);
    }
}
