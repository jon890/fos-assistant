package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
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
 * 다시 지워도 그대로 끝난다. 실패가 이어지는 대화는 기다리는 간격을 한 시간까지 두 배씩 늘린다. 이 간격은 메모리에만 둔다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationPurger {

    /** 한 차례에 보는 대화 수다. 지운 순서대로 본다. */
    private static final int BATCH = 50;

    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final ConversationRepository conversations;
    private final ConversationPurgeWriter writer;
    private final ConversationExecutionPurge executions;
    private final AgentService agents;
    private final HermesRunsClient hermes;
    private final ChatAttachmentRepository attachments;
    private final AttachmentStore attachmentStore;
    private final ArtifactStore artifactStore;
    private final Clock clock;

    /** 실패한 대화를 다음에 다시 볼 시각과 그때 기다릴 간격이다. */
    private final Map<Long, Backoff> backoffs = new ConcurrentHashMap<>();

    /** 운영은 매분 돈다. 검사에서는 {@code -} 로 끄고 {@link #purgeDue} 를 직접 부른다. */
    @Scheduled(cron = "${assistant.chat.purge-cron}")
    public void runScheduled() {
        purgeDue(clock.instant());
    }

    /**
     * 지웠지만 본문이 남은 대화를 지운다.
     *
     * @return 이번 차례에 지운 대화 수
     */
    public int purgeDue(Instant now) {
        List<Long> candidates = conversations.findPurgeCandidates(PageRequest.of(0, BATCH));
        int purged = 0;
        int waiting = 0;
        int failed = 0;
        for (Long conversationId : candidates) {
            Backoff backoff = backoffs.get(conversationId);
            if (backoff != null && now.isBefore(backoff.retryAt())) {
                continue;
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
                Duration next = backoff == null ? FIRST_BACKOFF : min(backoff.interval().multipliedBy(2), MAX_BACKOFF);
                backoffs.put(conversationId, new Backoff(now.plus(next), next));
                log.warn(
                        "지운 대화의 본문을 지우지 못했다 conversationId={} error={}",
                        conversationId,
                        ex.getClass().getSimpleName());
            }
        }
        if (purged > 0 || failed > 0) {
            log.info("지운 대화 정리 purged={} waiting={} failed={}", purged, waiting, failed);
        }
        return purged;
    }

    /**
     * 대화 하나를 지운다.
     *
     * @return 지웠으면 참. 실행이 아직 돌거나 다른 차례가 먼저 지웠으면 거짓
     */
    private boolean purgeOne(Long conversationId, Instant now) {
        if (!executions.settled(conversationId)) {
            return false;
        }
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null || conversation.purgedAt() != null) {
            return false;
        }
        deleteHermesSessions(conversation);
        deleteFiles(conversationId);
        return writer.purge(conversationId, now);
    }

    private void deleteHermesSessions(Conversation conversation) {
        List<ExecutionSessionRef> refs = new ArrayList<>(executions.sessions(conversation.id()));
        Agent conversationAgent = agents.findById(conversation.agentId()).orElse(null);
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
                // 주소를 알 길이 없다. 에이전트 줄은 지우지 않으므로 에이전트 없이 돈 실행만 여기 온다.
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

    /** 실패한 대화를 다시 볼 시각과 그때 쓴 간격이다. */
    private record Backoff(Instant retryAt, Duration interval) {}
}
