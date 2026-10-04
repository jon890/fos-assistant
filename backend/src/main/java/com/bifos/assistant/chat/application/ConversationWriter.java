package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ConversationRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화의 조건부 update 에 트랜잭션 경계를 준다.
 *
 * <p>저장소의 조건부 update 를 부르는 쪽의 트랜잭션에 참여시키고, 없으면 그 문장만의 트랜잭션을 연다.
 * 오래 도는 turn 은 일부러 트랜잭션 밖에서 이 update 를 부르므로, 부르는 메서드에 트랜잭션을 걸지 않고
 * 여기서 문장 하나만 감싼다. 각 메서드의 뜻은 {@link ConversationRepository} 의 같은 이름 메서드가 적는다.
 */
@Service
@RequiredArgsConstructor
public class ConversationWriter {

    private final ConversationRepository repository;

    @Transactional
    public int touchSession(Long id, String sessionId, Instant now) {
        return repository.touchSession(id, sessionId, now);
    }

    @Transactional
    public int assignSessionIfAbsent(Long id, String sessionId) {
        return repository.assignSessionIfAbsent(id, sessionId);
    }

    @Transactional
    public int replaceSessions(Long id, String sessionId) {
        return repository.replaceSessions(id, sessionId);
    }

    @Transactional
    public int resetAutoTurns(Long id) {
        return repository.resetAutoTurns(id);
    }

    @Transactional
    public int incrementAutoTurns(Long id) {
        return repository.incrementAutoTurns(id);
    }

    @Transactional
    public int fillTitleIfBlank(Long id, String title) {
        return repository.fillTitleIfBlank(id, title);
    }

    @Transactional
    public int renameIfActive(Long id, Long userId, String title, Instant now) {
        return repository.renameIfActive(id, userId, title, now);
    }

    @Transactional
    public int deleteIfActive(Long id, Long userId, Instant now) {
        return repository.deleteIfActive(id, userId, now);
    }
}
