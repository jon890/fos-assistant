package com.bifos.assistant.memory.maintenance.application.dto;

import com.bifos.assistant.memory.domain.type.MemoryScope;
import java.time.Instant;

/**
 * 사람이 확인할 정비 후보 한 줄이다. 제목은 후보를 만든 때가 아니라 지금 항목에서 읽는다.
 *
 * @param id 후보 사건의 번호. 확인 요청의 주소에 쓴다
 * @param action 동작 종류. {@code ARCHIVE} 나 {@code DEDUP}
 * @param summary 근거의 한국어 요약. 본문은 담지 않는다
 * @param archivable 지금 항목의 자동 보관 허락 표시
 */
public record MaintenanceCandidate(
    Long id,
    Long memoryId,
    MemoryScope scope,
    String title,
    String action,
    String summary,
    boolean archivable,
    Instant proposedAt) {
}
