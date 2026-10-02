package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.MemoryRevisionId;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 줄 하나를 쓰기 잠금으로 다시 읽어 암호화한다. 줄마다 트랜잭션 하나다(ADR-055).
 *
 * <p>대상을 찾은 뒤 잠그기까지의 사이에 다른 쪽이 그 줄을 고쳤을 수 있다. 잠근 뒤 조건을 다시 보고, 더는 대상이
 * 아니면 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemoryContentSealer {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryContentCipher cipher;

    /** @return 이 호출이 암호화했으면 true. 줄이 없거나 민감 줄이 아니거나 이미 암호문이면 false 다 */
    @Transactional
    public boolean sealMemory(Long id) {
        Memory row = memories.findByIdForUpdate(id).orElse(null);
        if (row == null || row.sensitivity() != MemorySensitivity.SENSITIVE || row.sealed()) {
            return false;
        }
        row.sealInPlace(cipher.seal(row.content(), row.contentBinding()));
        memories.save(row);
        return true;
    }

    /** @return 이 호출이 암호화했으면 true. 판이 없거나 민감 판이 아니거나 이미 암호문이면 false 다 */
    @Transactional
    public boolean sealRevision(MemoryRevisionId id) {
        MemoryRevision row = revisions.findByIdForUpdate(id).orElse(null);
        if (row == null || row.sensitivity() != MemorySensitivity.SENSITIVE || row.contentKeyId() != null) {
            return false;
        }
        row.sealInPlace(cipher.seal(row.content(), row.contentBinding()));
        revisions.save(row);
        return true;
    }
}
