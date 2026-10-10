package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ConversationExecutionPurge;
import com.bifos.assistant.usage.domain.ExecutionSessionRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사용자가 지운 대화의 본문을 실제로 지운다(ADR-20261008 / conversation-purge).
 *
 * <p>한 대화를 이 순서로 지운다. 도는 실행이나 읽는 중인 자식 사용량이 있으면 다음 차례로 미룬다.
 *
 * <ol>
 *   <li>Hermes 에서 그 대화의 session 을 지운다. 위임한 자식 session 은 Hermes 가 함께 지운다.
 *   <li>첨부 파일과 결과물 폴더를 지운다.
 *   <li>메시지, 대기 메시지, 첨부와 결과물의 줄, 실행의 본문을 한 트랜잭션으로 지우고 지운 시각을 적는다.
 * </ol>
 *
 * <p>어느 단계든 실패하면 그 대화는 지운 시각을 적지 않고 남는다. 다음 차례에 처음부터 다시 한다. 이미 지운 session 과 파일은
 * 다시 지워도 그대로 끝난다. 실패가 이어지는 대화는 기다리는 간격을 한 시간까지 두 배씩 늘리고, 그동안 후보에서 빼서 뒤에 지운
 * 대화가 밀리지 않게 한다. 다섯 번 잇달아 실패하면 error 로그를 남긴다. 이 간격은 메모리에만 둔다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationPurger {

    /** 한 차례에 보는 대화 수다. 지운 순서대로 본다. */
    private static final int BATCH = 50;

    /** 한 차례에 쓰는 시간의 상한이다. 정리는 다른 주기 작업과 scheduler 스레드 하나를 함께 쓴다. */
    private static final Duration TICK_BUDGET = Duration.ofSeconds(20);

    /** 이만큼 잇달아 실패하면 error 로그로 알린다. 운영자가 원인을 찾아야 하는 대화다. */
    private static final int ALERT_ATTEMPTS = 5;

    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /** 기다리는 대화가 없을 때 후보 질의에 넘기는 번호다. 대화 번호는 1부터다. */
    private static final List<Long> NONE_SKIPPED = List.of(-1L);

    private final ConversationRepository conversations;
    private final ConversationPurgeWriter writer;
    private final ConversationExecutionPurge executions;
    private final AgentService agents;
    private final HermesRunsClient hermes;
    private final ChatAttachmentRepository attachments;
    private final AttachmentStore attachmentStore;
    private final ArtifactStore artifactStore;
    private final Clock clock;
    private final ChatContentMutationCoordinator mutations;

    /** 실패한 대화를 다음에 다시 볼 시각과 그때 기다릴 간격, 잇단 실패 수다. */
    private final Map<Long, Backoff> backoffs = new ConcurrentHashMap<>();

    /** 운영은 매분 돈다. 검사에서는 {@code -} 로 끄고 {@link #purgeDue} 를 직접 부른다. */
    @Scheduled(cron = "${assistant.chat.purge-cron}")
    public void runScheduled() {
        purgeDue(clock.instant());
    }

    /**
     * 지웠지만 본문이 남은 대화를 지운다. 기다리는 간격 안의 대화는 후보에서 뺀다.
     *
     * @return 이번 차례에 지운 대화 수
     */
    public int purgeDue(Instant now) {
        List<Long> waitingIds = backoffs.entrySet().stream()
                .filter(entry -> now.isBefore(entry.getValue().retryAt()))
                .map(Map.Entry::getKey)
                .toList();
        List<Long> candidates = conversations.findPurgeCandidates(
                waitingIds.isEmpty() ? NONE_SKIPPED : waitingIds, PageRequest.of(0, BATCH));
        long started = System.nanoTime();
        int purged = 0;
        int waiting = 0;
        int failed = 0;
        for (Long conversationId : candidates) {
            if (Duration.ofNanos(System.nanoTime() - started).compareTo(TICK_BUDGET) > 0) {
                break;
            }
            try {
                if (purgeOne(conversationId, now)) {
                    purged++;
                    backoffs.remove(conversationId);
                } else {
                    waiting++;
                }
            } catch (RuntimeException ex) {
                failed++;
                recordFailure(conversationId, now, ex);
            }
        }
        if (purged > 0 || failed > 0) {
            log.info("지운 대화 정리 purged={} waiting={} failed={}", purged, waiting, failed);
        }
        return purged;
    }

    private void recordFailure(Long conversationId, Instant now, RuntimeException ex) {
        Backoff previous = backoffs.get(conversationId);
        Duration next =
                previous == null ? FIRST_BACKOFF : min(previous.interval().multipliedBy(2), MAX_BACKOFF);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        backoffs.put(conversationId, new Backoff(now.plus(next), next, attempts));
        String code = ex instanceof ApiException api ? api.code().name() : "-";
        if (attempts == ALERT_ATTEMPTS) {
            log.error(
                    "지운 대화의 본문을 잇달아 지우지 못했다 conversationId={} attempts={} error={} code={}",
                    conversationId,
                    attempts,
                    ex.getClass().getSimpleName(),
                    code);
        } else {
            log.warn(
                    "지운 대화의 본문을 지우지 못했다 conversationId={} attempts={} error={} code={}",
                    conversationId,
                    attempts,
                    ex.getClass().getSimpleName(),
                    code);
        }
    }

    /**
     * 대화 하나를 지운다.
     *
     * @return 지웠으면 참. 실행이 아직 돌거나 다른 차례가 먼저 지웠으면 거짓
     */
    private boolean purgeOne(Long conversationId, Instant now) {
        if (!executions.settled(conversationId, now)) {
            return false;
        }
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null) {
            return false;
        }
        var target = new ChatContentMutationTarget(conversationId, List.of());
        Conversation prepared = mutations.run(conversation.userId(), target, () -> {
            Conversation current = conversations.findById(conversationId).orElseThrow();
            if (current.deletedAt() == null || current.purgedAt() != null || !executions.settled(conversationId, now)) {
                return null;
            }
            for (ChatAttachment attachment : attachments.findByConversationIdOrderByIdAsc(conversationId)) {
                attachment.requestDeletion(now);
            }
            return current;
        });
        if (prepared == null) {
            return false;
        }
        deleteHermesSessions(prepared);
        deleteFiles(conversationId);
        return mutations.run(prepared.userId(), target, () -> writer.purge(conversationId, now));
    }

    private void deleteHermesSessions(Conversation conversation) {
        List<ExecutionSessionRef> refs = new ArrayList<>(executions.sessions(conversation.id()));
        Agent conversationAgent = agents.findById(conversation.agentId()).orElse(null);
        if (conversationAgent == null && conversation.hermesSessionId() != null) {
            log.warn("에이전트가 없어 대화의 Hermes session 을 지우지 못했다 conversationId={}", conversation.id());
        }
        if (conversationAgent != null) {
            for (String sessionId : new String[] {conversation.hermesSessionId(), conversation.hermesRootSessionId()}) {
                if (sessionId != null && !sessionId.isBlank()) {
                    refs.add(new ExecutionSessionRef(
                            conversationAgent.id(), conversationAgent.hermesProfile(), sessionId));
                }
            }
        }
        Map<Long, Agent> byId = agents.byIds(refs.stream()
                .map(ExecutionSessionRef::agentId)
                .filter(Objects::nonNull)
                .toList());
        Map<String, ExecutionSessionRef> unique = new LinkedHashMap<>();
        refs.forEach(ref -> unique.putIfAbsent(ref.profileName() + "\n" + ref.sessionId(), ref));
        for (ExecutionSessionRef ref : unique.values()) {
            Agent agent = ref.agentId() == null ? null : byId.get(ref.agentId());
            if (agent == null) {
                // 주소를 알 길이 없다. 지운 에이전트는 7일 뒤 행이 사라진다. 관리형 profile 의 session 은 profile 과 함께 이미 지워졌다.
                log.warn(
                        "주소를 몰라 Hermes session 을 지우지 못했다 conversationId={} profile={}",
                        conversation.id(),
                        ref.profileName());
                continue;
            }
            deleteSession(agent, ref);
        }
    }

    private void deleteSession(Agent agent, ExecutionSessionRef ref) {
        try {
            hermes.deleteSession(agent.apiBaseUrl(), ref.profileName(), ref.sessionId());
        } catch (ApiException ex) {
            // 지운 에이전트가 관리하던 profile 은 key 와 함께 통째로 지워졌다. 그 session 도 함께 사라졌다.
            if (ex.code() == ErrorCode.HERMES_PROFILE_KEY_MISSING && agent.isDeleted()) {
                return;
            }
            throw ex;
        }
    }

    private void deleteFiles(Long conversationId) {
        for (ChatAttachment attachment : attachments.findByConversationIdOrderByIdAsc(conversationId)) {
            if (attachment.deletedAt() == null) {
                attachmentStore.delete(attachment);
            }
        }
        artifactStore.deleteFolder(conversationId);
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    /** 실패한 대화를 다시 볼 시각과 그때 쓴 간격, 잇단 실패 수다. */
    private record Backoff(Instant retryAt, Duration interval, int attempts) {}
}
