package com.bifos.assistant.memory.domain;

import java.util.Optional;

/**
 * 읽어 온 기억과 revision 이 제목이나 본문을 처음 꺼낼 때 부르는 복호화 도구다(ADR-20261103 / memory-encryption).
 *
 * <p>읽을 때 붙인다. 엔티티는 푼 글을 저장하지 않는 필드에만 둔다.
 */
public interface MemoryTextOpener {

    /** @return 풀지 못하면 빈 값이다 */
    Optional<MemoryText> open(StoredMemoryText stored);
}
