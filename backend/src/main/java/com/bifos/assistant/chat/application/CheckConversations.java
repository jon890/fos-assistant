package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.model.OpenedCheck;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 먼저 살펴보기의 점검 대화를 찾고 만든다(ADR-077). 규칙은 {@code docs/backend/proactive-check.md} 의 「점검 대화」 가
 * 갖는다.
 *
 * <p>점검 대화는 사용자와 에이전트마다 지우지 않은 것 가운데 {@code id} 가 가장 큰 것 하나를 이어 쓴다. 요청자의 것만 찾고
 * 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckConversations {

    static final String TITLE_PREFIX = "먼저 살펴보기 · ";

    private static final int TITLE_MAX_LENGTH = 200;

    private final ConversationRepository conversations;
    private final UserExecutionLimiter limiter;
    private final Clock clock;

    /**
     * 찾기와 만들기를 사용자와 에이전트마다 차례로 하게 하는 잠금이다. 단추를 두 번 눌러도 점검 대화가 둘 생기지 않게 한다.
     *
     * <p>메모리에 있어 서버 한 대를 전제로 한다. 잠금 안에서 DB 를 부르므로 {@code synchronized} 가 아니라
     * {@link ReentrantLock} 을 쓴다.
     */
    private final Map<Key, ReentrantLock> locks = new ConcurrentHashMap<>();

    private record Key(Long userId, Long agentId) {}

    /** 그 사용자가 그 에이전트와 쓰는 점검 대화. 지운 것은 없는 것과 같다. */
    public Optional<Conversation> find(Long userId, Long agentId) {
        return conversations.findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(
                userId, agentId, ConversationPurpose.CHECK);
    }

    /**
     * 요청자의 점검 대화를 찾고, 없으면 만든다.
     *
     * <p>새로 만들기 전에 사용자 자리가 남았는지 본다(ADR-069). 없으면 아무것도 만들지 않고 {@code USER_BUSY} 다. 만들어
     * 두면 거절된 요청마다 빈 점검 대화가 목록에 남는다.
     *
     * @throws ApiException {@code USER_BUSY}. 점검 대화가 없고 사용자 자리도 없다
     */
    public OpenedCheck findOrCreate(CurrentUser user, Agent agent) {
        ReentrantLock lock = locks.computeIfAbsent(new Key(user.id(), agent.id()), key -> new ReentrantLock());
        lock.lock();
        try {
            Optional<Conversation> existing = find(user.id(), agent.id());
            if (existing.isPresent()) {
                return new OpenedCheck(existing.get(), false);
            }
            if (!limiter.hasTurnRoom(user.id())) {
                throw new ApiException(ErrorCode.USER_BUSY, "this user has reached the concurrent execution limit");
            }
            Conversation created = conversations.save(
                    Conversation.startedForCheck(user.id(), titleOf(agent), agent.id(), clock.instant()));
            return new OpenedCheck(created, true);
        } finally {
            lock.unlock();
        }
    }

    /** 시작이 거절돼 방금 만든 점검 대화를 지운다. 지우다 실패하면 경고 로그만 남기고 부르는 쪽의 원래 예외를 가리지 않는다. */
    public void deleteCreated(Long conversationId) {
        try {
            conversations.deleteById(conversationId);
        } catch (RuntimeException ex) {
            log.warn("시작을 거절한 요청의 빈 점검 대화를 지우지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /** 제목 칸의 길이를 넘으면 자른다. 자르는 자리가 두 글자로 된 문자의 가운데면 그 문자 앞에서 자른다. */
    private static String titleOf(Agent agent) {
        String title = TITLE_PREFIX + agent.name();
        if (title.length() <= TITLE_MAX_LENGTH) {
            return title;
        }
        int end = Character.isHighSurrogate(title.charAt(TITLE_MAX_LENGTH - 1))
                ? TITLE_MAX_LENGTH - 1
                : TITLE_MAX_LENGTH;
        return title.substring(0, end);
    }
}
