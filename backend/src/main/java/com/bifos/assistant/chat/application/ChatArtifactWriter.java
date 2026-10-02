package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 산출물 행의 조건부 update 에 트랜잭션 경계를 준다.
 *
 * <p>저장소의 조건부 update 를 부르는 쪽의 트랜잭션에 참여시키고, 없으면 그 문장만의 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class ChatArtifactWriter {

    private final ChatArtifactRepository repository;

    /** 그 파일을 가리키는 행 모두에 지운 시각을 적는다. 뜻은 {@link ChatArtifactRepository#markDeleted} 가 적는다. */
    @Transactional
    public int markDeleted(Long conversationId, String path, Instant now) {
        return repository.markDeleted(conversationId, path, now);
    }
}
