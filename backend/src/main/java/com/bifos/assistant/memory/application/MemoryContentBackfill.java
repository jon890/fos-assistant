package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동할 때 평문으로 남은 민감 줄을 암호화한다(ADR-055).
 *
 * <p>옛 판으로 되돌렸다 다시 올린 경우와 손으로 넣은 줄을 맡는다. 판 번호와 갱신 시각은 바꾸지 않는다. key 가 없으면
 * 옮기지 않고 줄 수만 경고하며, 기동을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryContentBackfill implements ApplicationRunner {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryContentCipher cipher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        sealPlaintext();
    }

    /** @return 암호화한 줄 수. key 가 없으면 0 이다 */
    @Transactional
    public int sealPlaintext() {
        List<Long> memoryIds = memories.findIdsBySensitivityAndContentKeyIdIsNull(MemorySensitivity.SENSITIVE);
        List<MemoryRevision> revisionRows =
                revisions.findBySensitivityAndContentKeyIdIsNull(MemorySensitivity.SENSITIVE);
        if (!cipher.enabled()) {
            if (!memoryIds.isEmpty() || !revisionRows.isEmpty()) {
                log.warn("평문으로 남은 민감 줄이 있다 memory={} revision={}", memoryIds.size(), revisionRows.size());
            }
            return 0;
        }
        int sealedMemories = 0;
        for (Long id : memoryIds) {
            // 번호를 읽은 뒤 사용자가 고쳤거나 다른 곳이 먼저 암호화했을 수 있어 잠가 다시 읽고 확인한다
            Memory row = memories.findByIdForUpdate(id).orElse(null);
            if (row == null || row.sensitivity() != MemorySensitivity.SENSITIVE || row.sealed()) {
                continue;
            }
            row.sealInPlace(cipher.seal(row.content(), row.contentBinding()));
            memories.save(row);
            sealedMemories++;
        }
        // memory_revision 은 판을 남긴 뒤 고치는 길이 없어 잠그지 않는다. 쓰는 곳은 이 보정뿐이다
        for (MemoryRevision row : revisionRows) {
            row.sealInPlace(cipher.seal(row.content(), row.contentBinding()));
            revisions.save(row);
        }
        int sealed = sealedMemories + revisionRows.size();
        if (sealed > 0) {
            log.info("평문으로 남은 민감 줄을 암호화했다 memory={} revision={}", sealedMemories, revisionRows.size());
        }
        return sealed;
    }
}
