package com.bifos.assistant.quality.application;

import com.bifos.assistant.chat.artifact.application.ArtifactVaultFinder;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.VaultFileType;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.quality.application.dto.CheckObservation;
import com.bifos.assistant.quality.application.dto.ChildObservation;
import com.bifos.assistant.quality.application.dto.ToolObservation;
import com.bifos.assistant.quality.application.dto.TurnObservation;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ConversationRootExecutions;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기능 하나가 돈 평가 대화에서 판정에 쓸 값을 모은다. 실행 줄과 사건, 답 메시지, 결과물, 보관 줄을 읽기만 한다.
 *
 * <p>판정은 {@link DeployCheckJudge} 가 이 값만 보고 한다. 모델이 쓴 글은 답 글 말고는 읽지 않는다.
 */
@Component
@RequiredArgsConstructor
class DeployCheckObserver {

    private final AgentExecutionRepository executions;
    private final ConversationRootExecutions rootExecutions;
    private final ExecutionEventRepository events;
    private final ChatService chat;
    private final ArtifactVaultFinder vault;
    private final ConnectorActionRepository connectorActions;


    /** 그 대화의 루트 실행이다. 만든 순서다. 사진 조회의 두 turn, 위임의 자동 turn 이 모두 든다. */
    List<AgentExecution> roots(Long conversationId) {
        return rootExecutions.findByConversationIdAndParentExecutionIdIsNullOrderByIdAsc(conversationId);
    }


    /** 루트 아래 실행이다. 루트 자신은 들지 않는다. */
    List<AgentExecution> descendants(Collection<Long> rootIds) {
        return rootIds.isEmpty() ? List.of() : executions.findByRootExecutionIdIn(rootIds).stream()
            .filter(execution -> !rootIds.contains(execution.id()))
            .toList();
    }


    /** 모델 호출 수다. 그 대화의 실행 줄 수(루트와 자손)로 센다. */
    int modelCalls(Long conversationId) {
        List<Long> rootIds = roots(conversationId).stream().map(AgentExecution::id).toList();
        return rootIds.size() + descendants(rootIds).size();
    }


    /**
     * 위임 기능이 끝났는지다. 루트의 위임 자식이 모두 끝났고, 그 결과를 전하는 묶음이 하나라도 끝난 상태다. 도는 turn 이 없는지는
     * 부르는 쪽이 본다.
     *
     * @return 위임 자식이 없으면 참. 바로 판정해 {@code NO_CHILD} 로 끝낸다
     */
    boolean delegationSettled(Long conversationId) {
        List<Long> rootIds = roots(conversationId).stream().map(AgentExecution::id).toList();
        List<AgentExecution> delegated = descendants(rootIds).stream()
            .filter(execution -> execution.delegationKey() != null)
            .toList();
        if (delegated.isEmpty()) {
            return true;
        }
        if (delegated.stream().anyMatch(execution -> execution.status() == ExecutionStatus.RUNNING)) {
            return false;
        }
        return chat.deliveryStates(conversationId).values().stream()
            .anyMatch(state -> state.status() != DeliveryStatus.DELIVERING);
    }


    /**
     * 판정에 쓸 값을 모은다.
     *
     * @param timedOut 기능 마감을 넘겼는가
     */
    CheckObservation observe(CurrentUser evaluation, Long conversationId, boolean timedOut) {
        List<AgentExecution> roots = roots(conversationId);
        List<Long> rootIds = roots.stream().map(AgentExecution::id).toList();
        List<AgentExecution> descendants = descendants(rootIds);
        List<Long> allIds = Stream.concat(rootIds.stream(), descendants.stream().map(AgentExecution::id)).toList();
        Map<Long, List<ExecutionEvent>> eventsByExecution = new HashMap<>();
        if (!allIds.isEmpty()) {
            for (ExecutionEvent event : events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(allIds)) {
                eventsByExecution.computeIfAbsent(event.executionId(), id -> new ArrayList<>()).add(event);
            }
        }
        List<ChatMessage> history = chat.history(evaluation, conversationId);
        Map<Long, ChatMessage> answers = new HashMap<>();
        for (ChatMessage message : history) {
            if (message.role() == MessageRole.ASSISTANT && message.executionId() != null) {
                answers.put(message.executionId(), message);
            }
        }
        Map<Long, List<ChatArtifact>> artifacts = chat.artifactsByMessage(history);
        boolean approvalRequested = !connectorActions.findByConversationIdAndUserIdAndStatusOrderByIdAsc(
            conversationId, evaluation.id(), ActionStatus.PENDING).isEmpty();

        List<TurnObservation> turns = new ArrayList<>();
        for (AgentExecution root : roots) {
            List<AgentExecution> tree = Stream.concat(
                Stream.of(root), descendants.stream().filter(execution -> root.id().equals(execution.rootExecutionId())))
                .toList();
            List<ToolObservation> tools = tree.stream()
                .flatMap(execution -> eventsByExecution.getOrDefault(execution.id(), List.of()).stream())
                .filter(event -> event.eventType() == ExecutionEventType.TOOL_COMPLETED)
                .map(event -> new ToolObservation(event.toolName(), Boolean.TRUE.equals(event.failed())))
                .toList();
            List<ChildObservation> children = descendants.stream()
                .filter(execution -> root.id().equals(execution.parentExecutionId()))
                .map(execution -> new ChildObservation(execution.delegationKey() != null, execution.status().name()))
                .toList();
            ChatMessage answer = answers.get(root.id());
            List<String> kinds = answer == null ? List.of() : artifacts.getOrDefault(answer.id(), List.of()).stream()
                .map(artifact -> VaultFileType.of(artifact.path()).map(type -> type.kind().name()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
            turns.add(new TurnObservation(
                answer == null ? "" : answer.content(),
                root.status().name(),
                root.errorCode(),
                tools,
                children,
                kinds,
                approvalRequested));
        }
        int vaultRows = vault.putIdsOf(evaluation.id(), conversationId).size();
        boolean delivered = chat.deliveryStates(conversationId).values().stream()
            .anyMatch(state -> state.status() == DeliveryStatus.DELIVERED);
        return new CheckObservation(turns, vaultRows, delivered, null, null, timedOut);
    }
}
