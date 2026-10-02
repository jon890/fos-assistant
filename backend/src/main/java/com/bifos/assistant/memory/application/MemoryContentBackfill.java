package com.bifos.assistant.memory.application;

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

/**
 * 기동할 때 평문으로 남은 민감 줄을 암호화한다(ADR-055).
 *
 * <p>옛 판으로 되돌렸다 다시 올린 경우와 손으로 넣은 줄을 맡는다. 판 번호와 갱신 시각은 바꾸지 않는다. key 가 없으면
 * 옮기지 않고 줄 수만 경고하며, 기동을 막지 않는다.
 *
 * <p>줄마다 쓰기 잠금으로 다시 읽는다. 실패해도 기동을 막지 않는다. 대상은 트랜잭션 밖에서 번호만 얻고, 줄 하나의
 * 잠금과 저장은 {@link MemoryContentSealer} 가 그 줄의 트랜잭션으로 한다. 한 줄이 실패하면 남은 줄은 다음 기동에서
 * 다시 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryContentBackfill implements ApplicationRunner {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryContentCipher cipher;
    private final MemoryContentSealer sealer;

    @Override
    public void run(ApplicationArguments args) {
        try {
            sealPlaintext();
        } catch (RuntimeException e) {
            // 예외 메시지와 스택은 본문이나 key 설정을 담을 수 있어 클래스 이름만 남긴다
            log.error("평문으로 남은 민감 줄의 보정이 실패했다 exception={}", e.getClass().getName());
        }
    }

    /** @return 실제로 암호화한 줄 수. key 가 없으면 0 이다 */
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
            if (sealer.sealMemory(id)) {
                sealedMemories++;
            }
        }
        int sealedRevisions = 0;
        for (MemoryRevision row : revisionRows) {
            if (sealer.sealRevision(row.id())) {
                sealedRevisions++;
            }
        }
        int sealed = sealedMemories + sealedRevisions;
        if (sealed > 0) {
            log.info("평문으로 남은 민감 줄을 암호화했다 memory={} revision={}", sealedMemories, sealedRevisions);
        }
        return sealed;
    }
}
