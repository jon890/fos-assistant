package com.bifos.assistant.shared;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.attention.domain.AttentionControlEntry;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.ExecutionQuestion;
import com.bifos.assistant.chat.delivery.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.entity.ContentDraft;
import com.bifos.assistant.chat.domain.entity.ContentDraftCreationRequest;
import com.bifos.assistant.chat.domain.entity.ContentDraftSnapshot;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.action.domain.ConnectorConversationGrant;
import com.bifos.assistant.connector.action.domain.ConnectorToolGrant;
import com.bifos.assistant.connector.action.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.crypto.domain.entity.UserDataKey;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.entity.MemoryCollection;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.check.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.domain.type.FindingKind;
import com.bifos.assistant.proactive.check.domain.type.ProblemStatus;
import com.bifos.assistant.shared.domain.CreatedAtEntity;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionContextSource;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.user.domain.AppUser;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 실제 factory로 감사 대상을 만들고 외래 키를 만족하는 선행 행을 준비한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class JpaAuditingEntityFixtures {
    static final Instant BUSINESS = Instant.parse("2020-01-01T00:00:00Z");


    record Fixture(List<CreatedAtEntity> rows, AppUser user, Agent agent, Conversation conversation,
        ConnectorConnection connection, ConnectorBinding binding, Memory memory, AgentExecution execution) {
    }


    static Fixture persistAll(EntityManager entityManager) {
        List<CreatedAtEntity> rows = new ArrayList<>();
        String unique = UUID.randomUUID().toString();
        long group = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1000;
        AppUser user = add(entityManager, rows, AppUser.of(unique + "@audit.test", "감사 검사", group, UserRole.ADMIN));
        Agent agent = add(entityManager, rows, Agent.of(unique, "감사 검사", unique, "http://runtime.test",
            CostMode.API, CredentialScope.DEDICATED, AgentVisibility.PRIVATE, user.id()));
        Conversation conversation = add(entityManager, rows, Conversation.startedBy(user.id(), "감사 검사", agent.id(), BUSINESS));
        AgentExecution execution = AgentExecution.builder().userId(user.id()).agentId(agent.id()).conversationId(conversation.id())
            .profileName(unique).status(ExecutionStatus.RUNNING).costMode(CostMode.API).startedAt(BUSINESS).build();
        entityManager.persist(execution);
        ConnectorConnection connection = add(entityManager, rows, ConnectorConnection.pending(user.id(), "audit", BUSINESS));
        ConnectorBinding binding = add(entityManager, rows, ConnectorBinding.pending(agent, connection, "audit", BUSINESS));
        ToolPolicyDecision decision = new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED);
        ConnectorAction action = ConnectorAction.decided(connection, agent.id(), execution, "audit", "audit", decision, false, unique, "f".repeat(64), BUSINESS);
        entityManager.persist(action);
        Memory memory = add(entityManager, rows, Memory.accepted(MemoryScope.USER, user.id(), null, "기억", StoredContent.plain("본문"),
            MemoryPlacement.core(MemoryRetrieval.SEARCH), user.id(), BUSINESS));
        ProactiveCheck check = ProactiveCheck.started(user.id(), agent.id(), conversation.id(), CheckTrigger.values()[0], false, BUSINESS);
        entityManager.persist(check);

        add(entityManager, rows, AllowedPerson.of(unique + "@audit.test", "허용", unique));
        add(entityManager, rows, AgentMemoryCollection.of(agent.id(), "core", false));
        add(entityManager, rows, MemoryCollection.defaultsFor(group).getFirst());
        add(entityManager, rows, AttentionControlEntry.hide(user.id(), CardKey.values()[0], "item", "state"));
        add(entityManager, rows, UserBrowser.create(user.id(), unique, BUSINESS));
        add(entityManager, rows, AgentToken.issueFor(unique, "f".repeat(64), "검사"));
        add(entityManager, rows, UserDataKey.wrapped(user.id(), "audit", "wrapped"));
        add(entityManager, rows, HermesSessionBinding.of(unique, unique, execution, "root", "parent"));
        add(entityManager, rows, ExecutionContextSource.of(execution.id(), 0, "MEMORY", "memory:" + memory.id(), "FULL", "FRESH"));
        add(entityManager, rows, Notification.of(user.id(), NotificationKind.TASK_SUCCEEDED, "알림", "본문", null));
        add(entityManager, rows, ChatArtifact.of(conversation.id(), 1L, unique, 1L));
        add(entityManager, rows, ChatAttachment.of(conversation.id(), user.id(), "audit.txt", "text/plain", 1, BUSINESS.plusSeconds(60)));
        add(entityManager, rows, ExecutionQuestion.of(execution.id(), 1L));
        add(entityManager, rows, ResultDelivery.opened(conversation.id(), BUSINESS));
        add(entityManager, rows, ConnectorToolGrant.of(user.id(), "audit", "audit", BUSINESS.plusSeconds(60)));
        add(entityManager, rows, ConnectorConversationGrant.of(action, BUSINESS.plusSeconds(60)));
        add(entityManager, rows, ConnectorActionExecution.stored(action, connection.id(), binding.id(), BUSINESS, BUSINESS,
            "{}", "{}", "{}", null, "f".repeat(64), "f".repeat(64), unique, "audit"));
        add(entityManager, rows, new ContentDraft(unique, conversation.id(), user.id(), BUSINESS.plusSeconds(60)));
        add(entityManager, rows, new ContentDraftCreationRequest(new byte[32], new byte[32], user.id(), conversation.id(), unique,
            execution.id(), execution.id(), "root", unique, new byte[32], unique));
        add(entityManager, rows, new ContentDraftSnapshot(UUID.randomUUID(), 1L, user.id(), conversation.id(), execution.id(), agent.id(),
            connection.id(), binding.id(), BUSINESS, BUSINESS, "f".repeat(64), 1, "audit", "f".repeat(64), "f".repeat(64), "f".repeat(64), BUSINESS.plusSeconds(60)));
        add(entityManager, rows, MemoryEmbedding.of(memory.id(), "audit", new byte[32], new float[] {1, 2}));
        add(entityManager, rows, MemoryCapture.of(memory.id(), user.id(), conversation.id(), execution.id(), MemoryCaptureKind.CREATED, 1, null, false));
        add(entityManager, rows, MemoryLink.related(memory.id(), memory.id() + 1, "audit", MemoryLinkOrigin.HUMAN, null, null));
        Task task = add(entityManager, rows, Task.check(user.id(), agent.id(), "예약"));
        add(entityManager, rows, TaskTrigger.once(task.id(), BUSINESS.plusSeconds(60), ZoneOffset.UTC, MissedPolicy.SKIP));
        add(entityManager, rows, ProactiveCheckFinding.of(check.id(), conversation.id(), FindingKind.NEW, null, "검사", unique, "발견", "https://example.test", BUSINESS));
        add(entityManager, rows, ProactiveCheckProblem.of(check.id(), conversation.id(), ProblemStatus.ACCEPTED, null, unique,
            "문제", null, null, null, null, null, null, null, null, List.of(), BUSINESS));
        return new Fixture(List.copyOf(rows), user, agent, conversation, connection, binding, memory, execution);
    }


    private static <T extends CreatedAtEntity> T add(EntityManager entityManager, List<CreatedAtEntity> rows, T entity) {
        entityManager.persist(entity);
        rows.add(entity);
        return entity;
    }
}
