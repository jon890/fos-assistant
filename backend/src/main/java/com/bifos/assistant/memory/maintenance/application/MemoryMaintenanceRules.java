package com.bifos.assistant.memory.maintenance.application;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.shared.util.Sha256;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 정비 후보를 고르는 규칙이다(ADR-20261015 / memory-maintenance). 읽기만 하고 기억을 바꾸지 않는다.
 *
 * <p>규칙이 본 신호 값은 후보 사건의 근거(JSON)에 남는다. 규칙을 바꾸면 {@link #RULE_VERSION} 을 올린다. 숫자는 제안 모드 첫 4주의 값이고
 * 확인 결과를 보고 고친다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MemoryMaintenanceRules {

    /** 규칙의 버전이다. 후보 사건의 근거에 남는다. */
    static final int RULE_VERSION = 1;

    /** 오래 실리지 않고 부정 신호가 쌓인 항목의 보관이다. */
    static final String ARCHIVE = "ARCHIVE";

    /** 같은 글(지문 일치)의 정리다. */
    static final String DEDUP = "DEDUP";


    /** 규칙이 고른 후보 하나다. 근거는 그때의 신호 값이다. */
    record Proposal(Memory memory, String action, Map<String, Object> evidence) {
    }


    /**
     * {@code ARCHIVE}: 승인됐고 보관하지 않았으며 기간 전에 만든 항목 가운데 기간 안에 실리지도 읽히지도 않았고, 「관련 없음」 이나 「틀림」 이
     * 한 번 이상 눌린 것. {@code archivable} 이 거짓이어도 후보는 만들고 근거에 그 값을 적는다. 자동 보관 여부는 단계 4 가 그 값으로 가른다.
     *
     * @param accepted 승인된 {@code MEMORY} 항목
     * @param loaded 기간 안에 한 번이라도 실리거나 읽힌 기억의 번호
     * @param unwanted 부정 단추 사건의 수. 기억 번호별이다
     */
    static List<Proposal> archive(
        List<Memory> accepted, Set<Long> loaded, Map<Long, Long> unwanted, int idleDays, Instant now) {
        Instant createdBefore = now.minus(Duration.ofDays(idleDays));
        List<Proposal> proposals = new ArrayList<>();
        for (Memory memory : accepted) {
            long marks = unwanted.getOrDefault(memory.id(), 0L);
            if (memory.retrieval() == MemoryRetrieval.ARCHIVE
                || loaded.contains(memory.id())
                || marks < 1
                || !memory.createdAt().isBefore(createdBefore)) {
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("idleDays", idleDays);
            evidence.put("unwantedMarks", marks);
            evidence.put("archivable", memory.archivable());
            evidence.put("retrieval", memory.retrieval().name());
            proposals.add(new Proposal(memory, ARCHIVE, evidence));
        }
        return proposals;
    }


    /**
     * {@code DEDUP}: 같은 주인과 같은 지문(제목과 본문의 해시)을 가진 항목이 둘 이상이면 오래된 쪽을 남기고 새 쪽을 후보로 둔다. 암호문으로
     * 저장된 민감 항목은 본문을 읽지 않으므로 보지 않는다.
     */
    static List<Proposal> dedup(List<Memory> accepted) {
        Map<String, List<Memory>> byFingerprint = new LinkedHashMap<>();
        for (Memory memory : accepted) {
            if (memory.sensitivity() == MemorySensitivity.SENSITIVE || memory.sealed()) {
                continue;
            }
            String fingerprint = Sha256.hex(
                memory.scope() + "\u0000" + owner(memory) + "\u0000" + memory.title() + "\u0000" + memory.content());
            byFingerprint.computeIfAbsent(fingerprint, key -> new ArrayList<>()).add(memory);
        }
        List<Proposal> proposals = new ArrayList<>();
        for (List<Memory> same : byFingerprint.values()) {
            if (same.size() < 2) {
                continue;
            }
            List<Memory> ordered = same.stream()
                .sorted(Comparator.comparing(Memory::createdAt).thenComparing(Memory::id))
                .toList();
            Memory keep = ordered.get(0);
            for (Memory duplicate : ordered.subList(1, ordered.size())) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("keepMemoryId", keep.id());
                evidence.put("sameCount", ordered.size());
                evidence.put("archivable", duplicate.archivable());
                proposals.add(new Proposal(duplicate, DEDUP, evidence));
            }
        }
        return proposals;
    }


    private static Long owner(Memory memory) {
        return memory.ownerUserId() != null ? memory.ownerUserId() : memory.groupId();
    }
}
