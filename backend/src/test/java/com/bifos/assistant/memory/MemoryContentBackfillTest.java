package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.memory.application.MemoryContentBackfill;
import com.bifos.assistant.memory.application.MemoryContentSealer;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.MemoryRevisionId;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 기동할 때 평문으로 남은 민감 줄을 암호화하는 보정을 본다(ADR-055). */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MemoryContentBackfillTest {

    private static final String MEMORY_MARK = "평문-표식-4410";
    private static final String REVISION_MARK = "평문-표식-4411";
    private static final String SEALED_FIRST = "v1.먼저-봉인-8820";

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

    @MockitoSpyBean
    MemoryContentSealer sealer;

    @BeforeEach
    void clean() {
        Mockito.reset(sealer);
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

    private void insertRevision(long memoryId, int revision, String content, String contentKeyId) {
        jdbc.update("""
                INSERT INTO memory_revision (memory_id, revision, change_type, scope, owner_user_id, collection,
                    entry_type, status, title, content, content_key_id, retrieval, sensitivity, changed_at)
                VALUES (?, ?, 'UPDATED', 'USER', 1, 'identity', 'MEMORY', 'ACCEPTED', '옛 판', ?, ?, 'SEARCH',
                    'SENSITIVE', CURRENT_TIMESTAMP(6))
                """, memoryId, revision, content, contentKeyId);
    }

    /** 대상을 찾은 뒤 잠가 읽기 전에 다른 쪽이 먼저 암호화한 상황을 만든다. */
    private void sealBehindBack(long id) {
        jdbc.update(
                "UPDATE memory SET content = ?, content_key_id = 'test-1', revision = 9 WHERE id = ?",
                SEALED_FIRST,
                id);
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

    @Test
    @DisplayName("이미 암호문인 민감 줄은 암호화하지 않고 본문을 그대로 둔다")
    void sealMemorySkipsAlreadySealedRow() {
        long id = insertMemory("SENSITIVE", MEMORY_MARK);
        sealBehindBack(id);

        assertThat(sealer.sealMemory(id)).isFalse();

        assertThat(jdbc.queryForObject("SELECT content FROM memory WHERE id = ?", String.class, id))
                .isEqualTo(SEALED_FIRST);
    }

    @Test
    @DisplayName("일반 줄과 없는 번호는 암호화하지 않는다")
    void sealMemorySkipsNormalAndMissingRow() {
        long normalId = insertMemory("NORMAL", "일반 본문");

        assertThat(sealer.sealMemory(normalId)).isFalse();
        assertThat(sealer.sealMemory(normalId + 1000)).isFalse();

        Map<String, Object> normal =
                jdbc.queryForMap("SELECT content, content_key_id FROM memory WHERE id = ?", normalId);
        assertThat(normal.get("CONTENT")).isEqualTo("일반 본문");
        assertThat(normal.get("CONTENT_KEY_ID")).isNull();
    }

    @Test
    @DisplayName("이미 암호문인 판과 없는 판은 암호화하지 않는다")
    void sealRevisionSkipsAlreadySealedAndMissingRevision() {
        long id = insertMemory("SENSITIVE", MEMORY_MARK);
        insertRevision(id, 1, SEALED_FIRST, "test-1");

        assertThat(sealer.sealRevision(new MemoryRevisionId(id, 1))).isFalse();
        assertThat(sealer.sealRevision(new MemoryRevisionId(id, 2))).isFalse();

        assertThat(jdbc.queryForObject("SELECT content FROM memory_revision WHERE memory_id = ?", String.class, id))
                .isEqualTo(SEALED_FIRST);
    }

    @Test
    @DisplayName("대상을 찾은 뒤 다른 쪽이 먼저 암호화한 줄을 먼저 읽어 둔 옛 값으로 덮어쓰지 않는다")
    void doesNotOverwriteRowSealedAfterLookup() {
        long id = insertMemory("SENSITIVE", MEMORY_MARK);
        doAnswer(invocation -> {
                    sealBehindBack(id);
                    return invocation.callRealMethod();
                })
                .when(sealer)
                .sealMemory(id);

        assertThat(backfill.sealPlaintext()).isZero();

        Map<String, Object> row = jdbc.queryForMap("SELECT content, revision FROM memory WHERE id = ?", id);
        assertThat(row.get("CONTENT")).isEqualTo(SEALED_FIRST);
        assertThat(((Number) row.get("REVISION")).longValue()).isEqualTo(9L);
    }

    @Test
    @DisplayName("대상을 찾은 뒤 사용자가 본문을 고치고 일반 항목으로 바꾼 줄은 덮어쓰지 않는다")
    void doesNotOverwriteRowChangedToNormalAfterLookup() {
        long id = insertMemory("SENSITIVE", MEMORY_MARK);
        doAnswer(invocation -> {
                    jdbc.update(
                            "UPDATE memory SET content = '고친 일반 본문', sensitivity = 'NORMAL', revision = 4 WHERE id = ?",
                            id);
                    return invocation.callRealMethod();
                })
                .when(sealer)
                .sealMemory(id);

        assertThat(backfill.sealPlaintext()).isZero();

        Map<String, Object> row =
                jdbc.queryForMap("SELECT content, content_key_id, revision FROM memory WHERE id = ?", id);
        assertThat(row.get("CONTENT")).isEqualTo("고친 일반 본문");
        assertThat(row.get("CONTENT_KEY_ID")).isNull();
        assertThat(((Number) row.get("REVISION")).longValue()).isEqualTo(4L);
    }

    @Test
    @DisplayName("보정이 예외를 던져도 기동을 잇고 로그에는 예외 클래스 이름만 남긴다")
    void runSwallowsFailureAndLogsClassNameOnly(CapturedOutput output) {
        long id = insertMemory("SENSITIVE", MEMORY_MARK);
        doThrow(new IllegalStateException(MEMORY_MARK)).when(sealer).sealMemory(id);

        assertThatCode(() -> backfill.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        assertThat(output.getAll()).contains("java.lang.IllegalStateException").doesNotContain(MEMORY_MARK);
    }
}
