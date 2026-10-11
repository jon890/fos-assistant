package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 잠가 읽은 기억의 판 저장과 민감 본문의 암호화를 맡는다. 트랜잭션은 호출한 서비스가 정한다. */
@Component
@RequiredArgsConstructor
class MemoryRevisionWriter {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryContentCipher cipher;
    private final Clock clock;


    Memory revise(
        CurrentUser user, Memory memory, String content, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        requirePlaceable(retrieval, sensitivity);
        // 판을 남기기 전에 암호화한다. key 가 없으면 여기서 거절돼 판도 본문도 바뀌지 않는다
        StoredContent body = stored(content, sensitivity, memory.contentBinding());
        // 제안 중복 키는 본문의 해시라, 민감 본문이면 지문이 평문 칸에 남는다. 민감 항목은 키를 비운다
        String dedupKey = memory.proposalDedupKey() == null || sensitivity == MemorySensitivity.SENSITIVE
            ? null
            : proposalDedupKey(memory.ownerUserId(), memory.title(), content);
        revisions.save(MemoryRevision.of(memory, MemoryChangeType.UPDATED, user.id(), null, clock.instant()));
        if (sensitivity == MemorySensitivity.SENSITIVE) {
            sealPlainRevisions(memory.id());
        }
        memory.revise(body, retrieval, sensitivity, dedupKey, clock.instant());
        return memories.save(memory);
    }


    /**
     * 한 항목의 평문 판을 모두 암호화한다. 판의 민감도와 번호와 시각은 그대로이고 저장 모양만 바뀐다(ADR-055).
     *
     * <p>평문 판은 두 경우에 생긴다. 일반 항목을 민감으로 바꿀 때와, 민감 항목을 일반으로 바꿨다가 다시 민감으로 바꿀
     * 때다. 방금 남긴 판도 평문이면 여기서 함께 암호화한다. 이미 민감 항목이었으면 평문 판이 없어 아무것도 하지 않는다.
     */
    private void sealPlainRevisions(Long memoryId) {
        // 방금 남긴 판이 조회에 들어오게 먼저 내보낸다. 조회한 줄은 이미 있는 줄이라 저장이 새 줄을 넣지 않는다
        revisions.flush();
        for (MemoryRevision row : revisions.findByIdMemoryIdAndContentKeyIdIsNull(memoryId)) {
            row.sealInPlace(cipher.seal(row.content(), row.contentBinding()));
            revisions.save(row);
        }
    }


    StoredContent stored(String plain, MemorySensitivity sensitivity, String binding) {
        return sensitivity == MemorySensitivity.SENSITIVE ? cipher.seal(plain, binding) : StoredContent.plain(plain);
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
