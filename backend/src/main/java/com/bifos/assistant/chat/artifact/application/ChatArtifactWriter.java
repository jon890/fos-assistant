package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;
import com.bifos.assistant.chat.artifact.domain.type.VaultOrigin;
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


    /** 그 대화에서 아직 답에 묶이지 않은 그림 행을 이 답에 묶는다. 뜻은 {@link ChatArtifactRepository#bindGeneratedImages} 가 적는다. */
    @Transactional
    public int bindGeneratedImages(Long conversationId, Long messageId) {
        return repository.bindGeneratedImages(conversationId, messageId);
    }


    /**
     * 그 대화에서 넣기 도구가 넣고 아직 답에 묶이지 않은 보관본을 이 답에 묶는다(ADR-20261119 / file-space-put).
     *
     * <p>먼저 이 답에 같은 경로의 결과물 줄(HTML, 그림)이 있으면 대화 폴더에서 넣은 보관본은 묶지 않고 들어온 길을 비운다.
     * 같은 파일이 답 아래에 두 줄로 보이지 않게 한다. HTML 을 묶은 뒤에 부른다.
     */
    @Transactional
    public int bindPutFiles(Long conversationId, Long messageId) {
        List<String> paths = repository.findPathsByMessageId(messageId);
        if (!paths.isEmpty()) {
            repository.dropPutOrigin(conversationId, VaultOrigin.PUT, paths);
        }
        return repository.bindPutFiles(conversationId, messageId, VaultOrigin.PUT_ORIGINS);
    }


    /**
     * {@code artifact_write} 가 제목을 받은 그 경로의 묶이지 않은 결과물 행을 이 답에 묶는다.
     *
     * @return 묶을 행이 있었으면 참. 없으면 부르는 쪽이 새 행을 만든다
     */
    @Transactional
    public boolean bindTitled(Long conversationId, String path, Long messageId, long byteSize) {
        return repository
            .findFirstByConversationIdAndPathAndMessageIdIsNullAndVaultUserIdIsNullAndGenerationCallKeyIsNullOrderByIdDesc(
                conversationId, path)
            .map(row -> repository.bindTitled(row.id(), messageId, byteSize) == 1)
            .orElse(false);
    }


    /**
     * {@code artifact_write} 가 받은 제목·설명의 암호문을 그 경로의 묶이지 않은 결과물 행에 둔다. 없으면 만들고 있으면 바꾼다.
     */
    @Transactional
    public void putWrittenTitle(Long conversationId, String path, long byteSize, String meta, Long metaKeyId) {
        repository
            .findFirstByConversationIdAndPathAndMessageIdIsNullAndVaultUserIdIsNullAndGenerationCallKeyIsNullOrderByIdDesc(
                conversationId, path)
            .ifPresentOrElse(
                row -> repository.retitle(row.id(), meta, metaKeyId),
                () -> repository.save(ChatArtifact.titled(conversationId, path, byteSize, meta, metaKeyId)));
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
