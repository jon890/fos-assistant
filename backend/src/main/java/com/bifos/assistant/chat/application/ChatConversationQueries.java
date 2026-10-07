package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.InternalValuePolicy;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** 대화와 실행 이력을 묶어 읽는다. */
@Component
@Slf4j
@RequiredArgsConstructor
class ChatConversationQueries {
    private final ConversationRepository conversations;
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final ExecutionRecorder executions;
    private final ExecutionEventRepository executionEvents;
    private final AgentExecutionRepository executionRepository;
    private final AttachmentService attachments;
    private final ArtifactService artifacts;
    private final TurnCancellation turns;
    private final Clock clock;
    private final List<CheckReportReads> checkReportReads;

    /**
     * 이 실행들 중 자식을 가진 것을 낸다.
     *
     * <p>대화 이력이 「이 답이 어떻게 만들어졌는지 보기」 를 어느 답에 붙일지 정하는 데 쓴다. 실행마다
     * 세지 않고 한 번에 읽는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작하므로 목록이 비면
     * 부르지 않는다.
     */
    Set<Long> executionIdsHavingChildren(List<ChatMessage> history) {
        List<Long> executionIds = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (executionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(executions.idsHavingChildren(executionIds));
    }

    /** 답 메시지의 실행 상태를 한 번에 읽는다. */
    Map<Long, ExecutionStatus> statuses(List<ChatMessage> history) {
        List<Long> ids = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return executionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AgentExecution::id, AgentExecution::status));
    }

    /**
     * 이 답들 중 막혀서 넘어간 것에 넘어간 곳의 provider 와 모델을 붙인다.
     *
     * <p>사건을 실행마다 세지 않고 한 번에 읽는다. 빈 {@code in} 절은 데이터베이스마다 다르게
     * 동작하므로 목록이 비면 부르지 않는다.
     *
     * <p>넘어간 곳의 모델은 내부 값이라 {@code ADMIN} 역할이 아니면 빈 묶음을 돌려준다(ADR-063).
     */
    Map<Long, String> switchedLabels(CurrentUser viewer, List<ChatMessage> history) {
        if (!InternalValuePolicy.visibleTo(viewer)) {
            return Map.of();
        }
        List<Long> executionIds = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (executionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> labels = new HashMap<>();
        for (ExecutionEvent event : executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(executionIds)) {
            if (event.eventType() == ExecutionEventType.PROVIDER_SWITCHED && event.detail() != null) {
                labels.put(event.executionId(), event.detail());
            }
        }
        return labels;
    }

    /** 답마다 도구와 하위 에이전트 사건을 한 번에 읽어 작업 과정 요약을 만든다. */
    Map<Long, ActivitySummary> activitySummaries(List<ChatMessage> history) {
        List<Long> rootIds = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (rootIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AgentExecution> roots = executionRepository.findAllById(rootIds).stream()
                .collect(Collectors.toMap(AgentExecution::id, it -> it));
        List<AgentExecution> descendants = executionRepository.findByRootExecutionIdIn(rootIds);
        Map<Long, Long> rootByExecution = new HashMap<>();
        rootIds.forEach(id -> rootByExecution.put(id, id));
        descendants.forEach(it -> rootByExecution.put(it.id(), it.rootExecutionId()));
        Map<Long, List<ExecutionEvent>> eventsByExecution =
                executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(rootByExecution.keySet()).stream()
                        .collect(Collectors.groupingBy(ExecutionEvent::executionId));
        Map<Long, ActivitySummary> summaries = new HashMap<>();
        for (Long rootId : rootIds) {
            AgentExecution root = roots.get(rootId);
            if (root == null) {
                continue;
            }
            List<AgentExecution> tree = new ArrayList<>();
            tree.add(root);
            descendants.stream()
                    .filter(it -> rootId.equals(it.rootExecutionId()))
                    .forEach(tree::add);
            int toolCount = 0;
            int subagentCount = 0;
            Instant latestFinish = null;
            for (AgentExecution execution : tree) {
                List<ExecutionEvent> events = eventsByExecution.getOrDefault(execution.id(), List.of());
                int toolStarts = 0;
                int toolCompletes = 0;
                int subagentStarts = 0;
                for (ExecutionEvent event : events) {
                    switch (event.eventType()) {
                        case TOOL_STARTED -> toolStarts++;
                        case TOOL_COMPLETED -> toolCompletes++;
                        case SUBAGENT_STARTED -> subagentStarts++;
                        default -> {}
                    }
                }
                toolCount += Math.max(toolStarts, toolCompletes);
                subagentCount += subagentStarts;
                if (!rootId.equals(execution.id()) && !events.isEmpty()) {
                    subagentCount++;
                }
                if (execution.finishedAt() != null
                        && (latestFinish == null || execution.finishedAt().isAfter(latestFinish))) {
                    latestFinish = execution.finishedAt();
                }
            }
            if (toolCount + subagentCount > 0) {
                Long durationMs = latestFinish == null
                        ? null
                        : Duration.between(root.startedAt(), latestFinish).toMillis();
                summaries.put(rootId, new ActivitySummary(toolCount, subagentCount, durationMs));
            }
        }
        return summaries;
    }

    /**
     * 이 대화의 첨부를 메시지 번호로 나눈다. 아직 메시지에 묶이지 않은 것은 뺀다.
     *
     * <p>메시지마다 묻지 않고 한 번에 읽는다. 지워진 첨부도 담아 지난 대화에 자리를 남긴다. 부르는
     * 순서에 기대지 않도록 여기서도 대화 주인을 확인한다.
     */
    Map<Long, List<ChatAttachment>> attachmentsByMessage(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        return attachments.allOf(conversation.id()).stream()
                .filter(it -> it.messageId() != null)
                .collect(Collectors.groupingBy(ChatAttachment::messageId));
    }

    /**
     * 답 메시지마다 그 turn 이 만든 결과물을 한 번에 읽는다. 사용자 메시지는 결과물이 없어 묻지 않는다.
     *
     * <p>{@link #history} 로 주인을 확인한 메시지 목록을 받는다.
     */
    Map<Long, List<ChatArtifact>> artifactsByMessage(List<ChatMessage> history) {
        return artifacts.byMessage(history.stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT)
                .map(ChatMessage::id)
                .toList());
    }

    /** 주인을 확인하고 메시지를 읽는다. 점검 대화면 그 대화의 열지 않은 보고를 연 것으로 적는다. */
    List<ChatMessage> history(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        markCheckReportsRead(user, conversation);
        return messages.findByConversationIdOrderByIdAsc(conversation.id());
    }

    /** 사용자가 점검 대화를 읽었거나 그 대화에 질문을 남겼다. 보고 열람 기록은 대화를 막지 않으므로 실패해도 넘어간다. */
    void markCheckReportsRead(CurrentUser user, Conversation conversation) {
        if (conversation.purpose() != ConversationPurpose.CHECK) {
            return;
        }
        for (CheckReportReads reads : checkReportReads) {
            try {
                reads.markRead(user.id(), conversation.id(), clock.instant());
            } catch (RuntimeException ex) {
                log.warn("점검 대화의 보고를 연 것으로 적지 못했다 conversationId={}", conversation.id(), ex);
            }
        }
    }

    /**
     * 대화에 지금 도는 turn 을 알려 준다.
     *
     * <p>주인 확인을 표시보다 먼저 한다. 남의 대화에 도는 turn 이 있는지 새지 않게 하려는 것이다.
     * 도는지는 실행 줄의 상태가 아니라 메모리 표시로 본다. 흐름은 루트 줄이 끝난 뒤에도 자식이 돈다.
     */
    RunningTurn running(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        TurnMark mark = turns.markOf(conversation.id());
        if (!mark.running()) {
            return new RunningTurn(false, null, null);
        }
        if (mark.executionId() == null) {
            return new RunningTurn(true, null, null);
        }
        Instant startedAt = executionRepository
                .findById(mark.executionId())
                .map(AgentExecution::startedAt)
                .orElse(null);
        return new RunningTurn(true, mark.executionId(), startedAt);
    }

    /**
     * 사용자의 대화를 최근에 바뀐 것부터 한 쪽 읽는다.
     *
     * @param cursor 앞 쪽이 돌려준 {@code nextCursor}. 처음이면 null
     * @param limit 한 쪽의 최대 개수. {@link ChatService#MAX_CONVERSATION_PAGE} 를 넘으면 그 값으로 줄인다
     */
    ConversationPage conversationsOf(CurrentUser user, String cursor, int limit) {
        int size = Math.clamp(limit, 1, ChatService.MAX_CONVERSATION_PAGE);
        // 한 줄을 더 읽어 다음 쪽이 있는지 안다. 개수를 한 쪽에 딱 맞게 읽으면 마지막 쪽에서도 빈 쪽을 한 번 더 부르게 된다.
        PageRequest window = PageRequest.ofSize(size + 1);
        List<Conversation> rows;
        if (cursor == null) {
            rows = conversations.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(user.id(), window);
        } else {
            ConversationCursor from = ConversationCursor.decode(cursor);
            rows = conversations.findPageAfter(user.id(), from.updatedAt(), from.id(), window);
        }
        if (rows.size() <= size) {
            return new ConversationPage(rows, null);
        }
        List<Conversation> items = rows.subList(0, size);
        return new ConversationPage(
                items, ConversationCursor.of(items.getLast()).encode());
    }

    /** 사용자의 대화 한 줄을 읽는다. 없거나 남의 것이면 같은 응답으로 숨긴다. */
    Conversation conversationOf(CurrentUser user, UUID publicId) {
        return access.requireOwn(user, publicId);
    }
}
