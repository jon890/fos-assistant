package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import java.time.Instant;
import java.util.List;
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
    public int markDeleted(Long conversationId, String path, Instant now, Instant createdBefore) {
        return repository.markDeleted(conversationId, path, now, createdBefore);
    }


    /** 지운 표시가 없고 {@code createdBefore} 보다 앞서 만든 행이다. */
    @Transactional(readOnly = true)
    public List<ChatArtifact> activeCreatedBefore(Instant createdBefore) {
        return repository.findByDeletedAtIsNullAndVaultUserIdIsNullAndCreatedAtBefore(createdBefore);
    }


    /** 그 답의 결과물 행을 자동 보관 차례에 둔다. 뜻은 {@link ChatArtifactRepository#markAutoVaultPending} 이 적는다. */
    @Transactional
    public int markAutoVaultPending(Long messageId) {
        return repository.markAutoVaultPending(messageId, AutoVaultState.PENDING);
    }


    /** 차례를 기다리는 줄을 맡는다. 참이면 이 호출이 맡았다. */
    @Transactional
    public boolean claimAutoVault(Long id) {
        return repository.claimAutoVault(id, AutoVaultState.PENDING, AutoVaultState.RUNNING) == 1;
    }


    /** 맡은 줄을 끝낸다. {@code to} 가 {@code PENDING} 이면 다음 훑기에 다시 돈다. */
    @Transactional
    public void finishAutoVault(Long id, AutoVaultState to, String error) {
        repository.moveAutoVault(id, AutoVaultState.RUNNING, to, to == AutoVaultState.FAILED ? error : null);
    }


    /** 실패한 줄을 처음 차례로 되돌린다. 참이면 되돌렸다. */
    @Transactional
    public boolean reopenAutoVault(Long id) {
        return repository.reopenAutoVault(id, AutoVaultState.FAILED, AutoVaultState.PENDING) == 1;
    }


    /** 재시작 뒤 맡은 작업이 없는 줄을 다시 차례에 둔다. */
    @Transactional
    public int requeueRunningAutoVault() {
        return repository.requeueRunning(AutoVaultState.RUNNING, AutoVaultState.PENDING);
    }
}
