package com.bifos.assistant.memory;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트가 기억 항목 줄의 {@code superseded_by_memory_id} 칸을 직접 넣고 읽는다. 운영 코드는 이 칸을 쓰는 경로가 없고, 옮기는 마이그레이션과 항목이
 * 지워질 때 비우는 저장소 쿼리만 이 칸을 바꾼다(ADR-20261102 / table-reduce-r2).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MemorySupersedeRows {

    /** 새 항목이 옛 항목을 대신한 것으로 적는다. */
    public static void supersede(JdbcTemplate jdbc, Long oldId, Long newId) {
        int updated = jdbc.update("UPDATE memory SET superseded_by_memory_id = ? WHERE id = ?", newId, oldId);
        if (updated != 1) {
            throw new IllegalStateException("no memory row " + oldId);
        }
    }


    /** 한 항목을 대신한 새 항목의 번호다. 대체되지 않았으면 null 이다. */
    public static Long supersededBy(JdbcTemplate jdbc, Long memoryId) {
        return jdbc.queryForObject("SELECT superseded_by_memory_id FROM memory WHERE id = ?", Long.class, memoryId);
    }


    /** 직전 이미지가 남긴 옛 연결 줄을 흉내 내 넣는다. 코드는 옛 테이블을 읽지 않고, 항목 삭제와 사용자 정리만 지운다. */
    public static void insertLegacyLink(JdbcTemplate jdbc, Long fromId, Long toId, String type, String status) {
        jdbc.update(
            "INSERT INTO memory_link (from_memory_id, to_memory_id, type, origin, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'AGENT', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            fromId,
            toId,
            type,
            status);
    }


    /** 옛 연결 줄의 수다. */
    public static int legacyLinkCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memory_link", Integer.class);
    }


    /** 옛 연결 줄을 모두 지운다. */
    public static void clearLegacyLinks(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM memory_link");
    }
}
