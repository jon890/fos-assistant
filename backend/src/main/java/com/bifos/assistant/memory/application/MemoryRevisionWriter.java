package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.SealedMemoryText;
import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.time.Clock;
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
    private final MemoryLinkRepository links;
    private final MemoryVectorCache vectorCache;
    private final MemoryTexts texts;
    private final Clock clock;


    Memory revise(
        CurrentUser user, Memory memory, String content, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        requirePlaceable(retrieval, sensitivity);
        if (!memory.readable()) {
            // 풀지 못한 줄은 제목도 모른다. 덮어쓰면 제목을 잃는다
            throw new ApiException(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE, "an unreadable memory cannot be revised");
        }
        // revision 을 남기기 전에 암호화한다. key 를 풀지 못하면 여기서 거절돼 revision 도 본문도 바뀌지 않는다
        Optional<SealedMemoryText> sealed = texts.seal(memory.stored(), memory.title(), content);
        // 제안 중복 키는 본문의 해시라, 민감 본문이면 지문이 평문 칸에 남는다. 민감 항목은 키를 비운다
        String dedupKey = memory.proposalDedupKey() == null || sensitivity == MemorySensitivity.SENSITIVE
            ? null
            : proposalDedupKey(memory.ownerUserId(), memory.title(), content);
        saveRevision(memory, MemoryChangeType.UPDATED, user);
        memory.revise(content, sealed.orElse(null), retrieval, sensitivity, dedupKey, clock.instant());
        return memories.save(memory);
    }


    /**
     * 지우기 전에 마지막 값을 버전으로 남기고 그 항목의 연결을 지운다. 연결은 외래 키가 없어 서비스가 같은 트랜잭션에서 지운다. 벡터는 항목 줄의 칸이라
     * 줄과 함께 지워진다.
     */
    void beforeDelete(CurrentUser user, Memory memory) {
        saveRevision(memory, MemoryChangeType.DELETED, user);
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


    static void requirePlaceable(MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        if (retrieval == null || sensitivity == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "retrieval and sensitivity are required");
        }
        if (!MemoryPlacement.allows(retrieval, sensitivity)) {
            throw new ApiException(ErrorCode.MEMORY_SENSITIVE_ALWAYS, "a sensitive memory cannot always be injected");
        }
    }


    static String proposalDedupKey(Long ownerUserId, String title, String content) {
        return Sha256.hex(ownerUserId + "\u0000" + title + "\u0000" + content);
    }
}
