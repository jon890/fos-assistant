package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.SealedMemoryText;
import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 잠가 읽은 기억의 revision 저장과 제목, 본문의 암호화, 지울 때 딸린 줄의 정리를 맡는다. 트랜잭션은 호출한 서비스가 정한다.
 *
 * <p>암호화는 {@link MemoryTexts} 가 한다(ADR-20261103 / memory-encryption).
 */
@Component
@RequiredArgsConstructor
class MemoryRevisionWriter {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryEmbeddingRepository embeddings;
    private final MemoryLinkRepository links;
    private final MemoryVectorCache vectorCache;
    private final MemoryTexts texts;
    private final Clock clock;


    Memory revise(CurrentUser user, Memory memory, String content, MemoryRetrieval retrieval) {
        requirePlaceable(retrieval);
        if (!memory.readable()) {
            // 풀지 못한 줄은 제목도 모른다. 덮어쓰면 제목을 잃는다
            throw new ApiException(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE, "an unreadable memory cannot be revised");
        }
        // revision 을 남기기 전에 암호화한다. key 를 풀지 못하면 여기서 거절돼 revision 도 본문도 바뀌지 않는다
        Optional<SealedMemoryText> sealed = texts.seal(memory.stored(), memory.title(), content);
        // 제안 중복 키는 주인 key 의 HMAC 이다. 평문의 해시를 남기지 않는다
        String dedupKey = memory.proposalDedupKey() == null
            ? null
            : texts.proposalDedupKey(memory.ownerUserId(), memory.title(), content);
        saveRevision(memory, MemoryChangeType.UPDATED, user);
        memory.revise(content, sealed.orElse(null), retrieval, dedupKey, clock.instant());
        return memories.save(memory);
    }


    /**
     * 지우기 전에 마지막 값을 버전으로 남기고 그 항목의 연결을 지운다. 연결은 외래 키가 없어 서비스가 같은 트랜잭션에서 지운다. 벡터는 항목 줄의 칸이라
     * 줄과 함께 지워지고, 직전 이미지가 옛 테이블에 남긴 줄만 따로 지운다.
     */
    void beforeDelete(CurrentUser user, Memory memory) {
        saveRevision(memory, MemoryChangeType.DELETED, user);
        embeddings.deleteLegacyRows(List.of(memory.id()));
        links.deleteByMemoryId(memory.id());
        vectorCache.evict(memory.id());
    }


    /**
     * 지금 값을 revision 으로 남긴다. 지금 줄이 데이터 key 의 암호문이면 같은 AAD 라 그대로 옮긴다. 아직 이관하지 않은 줄이면 revision 은
     * 주인 데이터 key 로 암호화해 남긴다.
     */
    private void saveRevision(Memory memory, MemoryChangeType changeType, CurrentUser user) {
        MemoryRevision revision = MemoryRevision.of(memory, changeType, user.id(), null, clock.instant());
        if (memory.dataKeyId() == null && memory.readable()) {
            texts.seal(revision.stored(), memory.title(), memory.content()).ifPresent(revision::seal);
        }
        revisions.save(revision);
    }


    static void requirePlaceable(MemoryRetrieval retrieval) {
        if (retrieval == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "retrieval is required");
        }
    }
}
