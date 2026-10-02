package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryContentBackfill;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 기동할 때 평문으로 남은 민감 줄을 암호화하는 보정을 본다(ADR-054). */
@SpringBootTest
@ActiveProfiles("test")
class MemoryContentBackfillTest {

    private static final String MEMORY_MARK = "평문-표식-4410";
    private static final String REVISION_MARK = "평문-표식-4411";

    @Autowired
    MemoryContentBackfill backfill;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        revisionRepository.deleteAll();
        repository.deleteAll();
    }

    private long insertMemory(String sensitivity, String content) {
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, collection, entry_type, title, content, retrieval,
                    always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES ('USER', 1, 'identity', 'MEMORY', '직접 넣은 줄', ?, 'SEARCH', FALSE, ?, 3, 'ACCEPTED',
                    TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-02 00:00:00')
                """, content, sensitivity);
        return jdbc.queryForObject("SELECT MAX(id) FROM memory", Long.class);
    }

    @Test
    @DisplayName("평문으로 남은 민감 줄만 암호화하고 판 번호와 갱신 시각은 그대로다")
    void sealsPlainSensitiveRowsOnly() {
        long sensitiveId = insertMemory("SENSITIVE", MEMORY_MARK);
        long normalId = insertMemory("NORMAL", "일반 본문");
        jdbc.update("""
                INSERT INTO memory_revision (memory_id, revision, change_type, scope, owner_user_id, collection,
                    entry_type, status, title, content, retrieval, sensitivity, changed_at)
                VALUES (?, 1, 'UPDATED', 'USER', 1, 'identity', 'MEMORY', 'ACCEPTED', '옛 판', ?, 'SEARCH',
                    'SENSITIVE', CURRENT_TIMESTAMP(6))
                """, sensitiveId, REVISION_MARK);
        Map<String, Object> before =
                jdbc.queryForMap("SELECT revision, updated_at FROM memory WHERE id = ?", sensitiveId);

        assertThat(backfill.sealPlaintext()).isEqualTo(2);

        Map<String, Object> sealed = jdbc.queryForMap(
                "SELECT content, content_key_id, revision, updated_at FROM memory WHERE id = ?", sensitiveId);
        assertThat((String) sealed.get("CONTENT")).doesNotContain(MEMORY_MARK);
        assertThat(sealed.get("CONTENT_KEY_ID")).isEqualTo("test-1");
        assertThat(sealed.get("REVISION")).isEqualTo(before.get("REVISION"));
        // 같은 시각이 다른 offset 으로 읽힐 수 있어 순간으로 비교한다
        assertThat(((OffsetDateTime) sealed.get("UPDATED_AT")).toInstant())
                .isEqualTo(((OffsetDateTime) before.get("UPDATED_AT")).toInstant());
        Map<String, Object> revision = jdbc.queryForMap("SELECT content, content_key_id FROM memory_revision");
        assertThat((String) revision.get("CONTENT")).doesNotContain(REVISION_MARK);
        assertThat(revision.get("CONTENT_KEY_ID")).isEqualTo("test-1");
        Map<String, Object> normal =
                jdbc.queryForMap("SELECT content, content_key_id FROM memory WHERE id = ?", normalId);
        assertThat(normal.get("CONTENT")).isEqualTo("일반 본문");
        assertThat(normal.get("CONTENT_KEY_ID")).isNull();
        assertThat(memories.contentOf(repository.findById(sensitiveId).orElseThrow()))
                .isEqualTo(MEMORY_MARK);
    }

    @Test
    @DisplayName("한 번 더 부르면 0 이고 암호문이 바뀌지 않는다")
    void secondRunChangesNothing() {
        long id = insertMemory("SENSITIVE", MEMORY_MARK);
        backfill.sealPlaintext();
        String first = jdbc.queryForObject("SELECT content FROM memory WHERE id = ?", String.class, id);

        assertThat(backfill.sealPlaintext()).isZero();

        assertThat(jdbc.queryForObject("SELECT content FROM memory WHERE id = ?", String.class, id))
                .isEqualTo(first);
    }
}
