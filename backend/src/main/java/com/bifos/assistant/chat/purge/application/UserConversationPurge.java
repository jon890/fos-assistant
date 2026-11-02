package com.bifos.assistant.chat.purge.application;

import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 대화 쪽을 맡는다(ADR-20261101 / user-purge).
 *
 * <p>대화는 지운 것으로만 적는다. Hermes session, 첨부, 결과물, 메시지는 {@link ConversationPurger} 가 지운다. 사용자 정리는 그 사용자의
 * 대화가 모두 정리될 때까지 데이터 key 와 사용자 줄을 지우지 않는다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class UserConversationPurge {

    private final ConversationRepository conversations;


    /**
     * 그 사용자의 지우지 않은 대화 모두에 지운 시각을 적는다. 이미 지운 대화는 시각을 바꾸지 않으므로 다시 불러도 같다.
     *
     * @return 이번에 지운 것으로 적은 대화 수
     */
    public int deleteAll(Long userId, Instant now) {
        return conversations.deleteAllActiveOf(userId, now);
    }


    /** 그 사용자의 대화가 모두 본문까지 정리됐는가. 대화가 없어도 참이다. */
    public boolean allPurged(Long userId) {
        return conversations.countByUserIdAndPurgedAtIsNull(userId) == 0;
    }
}
