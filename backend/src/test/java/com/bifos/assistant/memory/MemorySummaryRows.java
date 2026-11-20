package com.bifos.assistant.memory;

import com.bifos.assistant.memory.application.MemoryTexts;
import com.bifos.assistant.memory.domain.entity.Memory;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트가 기억 줄의 요약 칸과 그룹의 기억 조립 예산을 직접 적는다. 운영에서 요약은 {@code memory_remember} 와 기동 이관이 적고, 예산은 관리자
 * API 가 적는다({@code docs/features/memory/design.md}).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MemorySummaryRows {

    /** 요약 기능 전의 예산이다. 기억 전체 8,000자, 개인 사실 구역 2,000자에 맞춰 쓴 테스트가 쓴다. */
    public static final String LEGACY_BUDGET = "{\"version\":1,\"summaryMaxChars\":2000,\"maxChars\":8000}";


    /** 그 기억에 요약을 적는다. 암호화가 켜진 설치면 주인 key 의 암호문으로 적는다. */
    public static void summarize(MemoryTexts texts, JdbcTemplate jdbc, Memory memory, String summary) {
        String stored = texts.sealSummary(memory.stored(), summary).orElse(summary);
        int updated = jdbc.update("UPDATE memory SET summary = ? WHERE id = ?", stored, memory.id());
        if (updated != 1) {
            throw new IllegalStateException("no memory row " + memory.id());
        }
    }


    /** 배포 때 이관처럼 본문을 그대로 요약으로 적는다. */
    public static void summarizeAsContent(MemoryTexts texts, JdbcTemplate jdbc, Memory memory) {
        summarize(texts, jdbc, memory, memory.content());
    }


    /** 그룹의 예산을 정한다. 같은 컨텍스트의 다른 테스트로 새지 않게 {@link #clearBudget} 으로 지운다. */
    public static void useBudget(JdbcTemplate jdbc, Long groupId, String json) {
        clearBudget(jdbc, groupId);
        jdbc.update(
            "INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (?, 'MEMORY_CONTEXT', ?)", groupId, json);
    }


    public static void clearBudget(JdbcTemplate jdbc, Long groupId) {
        jdbc.update("DELETE FROM group_setting WHERE group_id = ? AND setting_key = 'MEMORY_CONTEXT'", groupId);
    }
}
