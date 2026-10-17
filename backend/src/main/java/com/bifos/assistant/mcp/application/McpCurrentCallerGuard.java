package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 실행 전후 현재 origin과 사용자·에이전트 권한을 별도 트랜잭션에서 확인한다. */
@Service
public class McpCurrentCallerGuard {
    private final UserAccessPolicy users;
    private final AgentService agents;
    private final SessionOwnerResolver sessions;
    private final TransactionTemplate permissions;


    public McpCurrentCallerGuard(UserAccessPolicy users, AgentService agents,
        SessionOwnerResolver sessions, PlatformTransactionManager manager) {
        this.users = users;
        this.agents = agents;
        this.sessions = sessions;
        this.permissions = new TransactionTemplate(manager);
        permissions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        permissions.setReadOnly(true);
    }


    public void requireCurrent(McpCaller caller) {
        permissions.executeWithoutResult(status -> {
            var original = caller.originExecution();
            var context = caller.context();
            var origin = sessions.resolve(original.profileName(), context.rootSessionId(), context.sessionId());
            if (!original.id().equals(origin.id())
                || !caller.user().id().equals(origin.userId())
                || origin.conversationId() == null
                || !origin.conversationId().equals(original.conversationId())
                || !original.profileName().equals(origin.profileName())
                || !users.allowed(origin.userId())) {
                throw invalidContext();
            }
            var agent = agents.findById(origin.agentId()).orElseThrow(McpCurrentCallerGuard::invalidContext);
            if (agent.isDeleted()
                || !agent.enabled()
                || !agent.isReadableBy(origin.userId())
                || !origin.profileName().equals(agent.hermesProfile())) {
                throw invalidContext();
            }
        });
    }


    private static ApiException invalidContext() {
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }

}
