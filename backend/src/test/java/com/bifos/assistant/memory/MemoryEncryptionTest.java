package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryContentCipher;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.MemorySources;
import com.bifos.assistant.memory.application.MemoryTextMigration;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.memory.presentation.MemoryController;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MysqlCase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 기억의 제목과 본문이 데이터베이스에 주인 데이터 key 의 암호문으로만 남는지 본다(ADR-20261103 / memory-encryption).
 *
 * <p>데이터베이스는 JdbcTemplate 으로 직접 읽는다. 엔티티는 저장소로 다시 읽어 푼 글을 본다. 같은 검사를
 * {@code MemoryEncryptionMysqlTest} 가 실제 MySQL 에서 돌린다.
 */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MemoryEncryptionTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@example.com", "admin", 1L, UserRole.ADMIN);
    private static final String TITLE = "제목-표식-2290";
    private static final String FIRST = "평문-표식-7391";
    private static final String SECOND = "평문-표식-8802";

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryContentCipher legacyCipher;

    @Autowired
    MemoryTextMigration migration;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    ContextAssembler context;

    @Autowired
    MemorySources sources;

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    JdbcTemplate jdbc;


    @BeforeEach
    void clean() {
        revisionRepository.deleteAll();
        repository.deleteAll();
    }


    private Map<String, Object> memoryRow(Long id) {
        return jdbc.queryForMap("SELECT title, content, data_key_id, content_key_id FROM memory WHERE id = ?", id);
    }


    private List<Map<String, Object>> revisionRows(Long memoryId) {
        return jdbc.queryForList(
            "SELECT title, content, data_key_id, content_key_id FROM memory_revision WHERE memory_id = ? ORDER BY revision",
            memoryId);
    }


    private static void assertSealed(Map<String, Object> row, String... plains) {
        assertThat((String)row.get("TITLE")).startsWith("v1.");
        assertThat((String)row.get("CONTENT")).startsWith("v1.");
        for (String plain : plains) {
            assertThat((String)row.get("TITLE")).doesNotContain(plain);
            assertThat((String)row.get("CONTENT")).doesNotContain(plain);
        }
        assertThat(row.get("DATA_KEY_ID")).isNotNull();
        assertThat(row.get("CONTENT_KEY_ID")).isNull();
    }


    private Memory reread(Long id) {
        return repository.findById(id).orElseThrow();
    }


    private List<MemoryView> listOf(CurrentUser user) {
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        Mockito.when(currentUser.require()).thenReturn(user);
        return new MemoryController(memories, context, sources, linkService, currentUser).readable();
    }


    private Long insertRow(String scope, Long owner, Long group, String title, String content, String legacyKeyId) {
        jdbc.update(
            """
                INSERT INTO memory (scope, owner_user_id, group_id, collection, entry_type, title, content, content_key_id,
                    retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES (?, ?, ?, 'identity', 'MEMORY', ?, ?, ?, 'SEARCH', FALSE, ?, 1, 'ACCEPTED',
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
            scope, owner, group, title, content, legacyKeyId, legacyKeyId == null ? "NORMAL" : "SENSITIVE");
        return jdbc.queryForObject("SELECT MAX(id) FROM memory", Long.class);
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("화면, 그룹, 민감 문서, 제안으로 만든 기억은 제목과 본문이 모두 데이터 key 의 암호문이고 다시 읽으면 원래 글이다")
    void everyCreatePathStoresCiphertext() {
        Memory normal = memories.create(ADMIN, MemoryScope.USER, TITLE, FIRST, false);
        Memory group = memories.create(
            ADMIN, MemoryScope.GROUP, TITLE, FIRST, "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        Memory document = memories.createDocument(ADMIN, "core", "doc-1", TITLE, FIRST, MemorySensitivity.SENSITIVE);
        Memory proposed = memories.proposeUser(ADMIN, TITLE, FIRST, 1L);

        for (Memory memory : List.of(normal, group, document, proposed)) {
            assertSealed(memoryRow(memory.id()), TITLE, FIRST);
            assertThat(reread(memory.id())).extracting(Memory::title, Memory::content).containsExactly(TITLE, FIRST);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_data_key WHERE group_id = 1", Long.class))
            .as("그룹 기억은 그룹 데이터 key 한 줄을 만든다")
            .isEqualTo(1L);
        assertThat(memoryRow(group.id()).get("DATA_KEY_ID")).isNotEqualTo(memoryRow(normal.id()).get("DATA_KEY_ID"));
    }


    @Test
    @DisplayName("고치면 지금 줄과 물러난 revision 이 모두 암호문이고 지우면 DELETED revision 도 암호문이며 revision 은 원래 글로 읽힌다")
    void reviseAndDeleteKeepCiphertextOnly() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, TITLE, FIRST, false);

        memories.update(ADMIN, memory.id(), SECOND, false, null);
        assertSealed(memoryRow(memory.id()), TITLE, FIRST, SECOND);
        assertThat(reread(memory.id()).content()).isEqualTo(SECOND);

        memories.delete(ADMIN, memory.id());

        assertThat(revisionRows(memory.id())).hasSize(2).allSatisfy(row -> assertSealed(row, TITLE, FIRST, SECOND));
        assertThat(memories.revisionsOf(ADMIN, memory.id()))
            .extracting(MemoryRevision::title, MemoryRevision::content)
            .containsExactly(
                tuple(TITLE, FIRST), tuple(TITLE, SECOND));
    }


    @Test
    @DisplayName("암호문을 다른 기억이나 다른 컬럼으로 옮기거나 주인을 바꾸면 풀리지 않고 그 줄만 「읽지 못함」 이다")
    void movedCiphertextDoesNotOpen() {
        Memory source = memories.create(ADMIN, MemoryScope.USER, TITLE, FIRST, false);
        Memory target = memories.create(ADMIN, MemoryScope.USER, "다른 제목", SECOND, false);
        Memory swapped = memories.create(ADMIN, MemoryScope.USER, "세 번째", SECOND, false);
        Memory moved = memories.create(ADMIN, MemoryScope.USER, "네 번째", SECOND, false);
        Map<String, Object> sourceRow = memoryRow(source.id());
        Map<String, Object> swappedRow = memoryRow(swapped.id());
        jdbc.update("UPDATE memory SET content = ? WHERE id = ?", sourceRow.get("CONTENT"), target.id());
        jdbc.update(
            "UPDATE memory SET title = ?, content = ? WHERE id = ?",
            swappedRow.get("CONTENT"), swappedRow.get("TITLE"), swapped.id());
        jdbc.update("UPDATE memory SET owner_user_id = 2 WHERE id = ?", moved.id());

        for (Long id : List.of(target.id(), swapped.id(), moved.id())) {
            Memory read = reread(id);
            assertThat(read.readable()).isFalse();
            assertThat(read.title()).isEqualTo(Memory.UNREADABLE_TITLE);
            assertThat(read.content()).isEmpty();
        }
        assertThat(reread(source.id()).content()).isEqualTo(FIRST);
    }


    @Test
    @DisplayName("풀지 못한 줄은 목록에서 「읽지 못함」 이고 목록은 실패하지 않으며 싣지도 고치지도 본문을 내지도 않는다")
    void unreadableRowIsListedButNotUsed() {
        Memory readable = memories.create(ADMIN, MemoryScope.USER, TITLE, FIRST, true);
        Memory broken = memories.create(ADMIN, MemoryScope.USER, "깨질 제목", SECOND, true);
        jdbc.update("UPDATE memory SET title = 'v1.AAAAAAAAAAAAAAAA.AAAA' WHERE id = ?", broken.id());

        assertThat(listOf(ADMIN))
            .extracting(MemoryView::id, MemoryView::title, MemoryView::content, MemoryView::unreadable)
            .containsExactly(
                tuple(readable.id(), TITLE, FIRST, false),
                tuple(broken.id(), Memory.UNREADABLE_TITLE, "", true));
        assertThat(memories.alwaysInjectedFor(ADMIN, MemoryAccess.of(Set.of("core"), Set.of())))
            .extracting(Memory::id)
            .containsExactly(readable.id());
        assertCode(() -> memories.update(ADMIN, broken.id(), "덮어쓰기", false, null), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        assertCode(() -> memories.contentOf(reread(broken.id())), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        memories.delete(ADMIN, broken.id());
        assertThat(repository.findById(broken.id())).isEmpty();
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("KEK 가 있는데 주인 데이터 key 를 풀지 못하면 저장을 거절하고 줄을 남기지 않는다")
    void unusableDataKeyRejectsSave() {
        CurrentUser locked = new CurrentUser(4242L, "locked@example.com", "locked", 42L, UserRole.ADMIN);
        jdbc.update(
            "INSERT INTO user_data_key (user_id, kek_id, wrapped_key, created_at) VALUES (4242, 'missing-kek', 'v1.x.y', CURRENT_TIMESTAMP(6))");
        long before = repository.count();

        assertCode(() -> memories.create(locked, MemoryScope.USER, TITLE, FIRST, false), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        assertCode(() -> memories.proposeUser(locked, TITLE, FIRST, 1L), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);

        assertThat(repository.count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory WHERE content LIKE ?", Long.class, "%" + FIRST + "%"))
            .isZero();
    }


    @Test
    @DisplayName("민감 항목은 지금처럼 목록에서 본문이 비고 목록 수정 경로로 고치지 못하며 민감으로 고치면 제안 중복 키를 비운다")
    void sensitiveRulesStayUntilTheyAreRemoved() {
        Memory sensitive = memories.create(
            ADMIN, MemoryScope.USER, TITLE, FIRST, "identity", MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
        assertThat(listOf(ADMIN)).singleElement().satisfies(view -> {
            assertThat(view.content()).isEmpty();
            assertThat(view.sensitive()).isTrue();
        });
        assertCode(() -> memories.update(ADMIN, sensitive.id(), "x", false, null), ErrorCode.MEMORY_SENSITIVE_NOT_EDITABLE);

        Memory proposed = memories.proposeUser(ADMIN, "제안", FIRST, 1L);
        memories.accept(ADMIN, proposed.id());
        memories.update(ADMIN, proposed.id(), SECOND, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
        assertThat(jdbc.queryForObject("SELECT proposal_dedup_key FROM memory WHERE id = ?", String.class, proposed.id()))
            .isNull();
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("기동 이관은 평문 줄과 옛 key 줄, revision 을 데이터 key 로 다시 쓰고 글의 해시가 같으며 두 번 돌려도 바뀌지 않는다")
    void migrationRewritesPlainAndLegacyRows() {
        StoredContent legacy = legacyCipher.seal(SECOND, "USER:1");
        Long legacyId = insertRow("USER", 1L, null, "옛 민감 제목", legacy.content(), legacy.keyId());
        Long plainId = insertRow("GROUP", null, 1L, TITLE, FIRST, null);
        jdbc.update(
            """
                INSERT INTO memory_revision (memory_id, revision, change_type, scope, owner_user_id, collection, entry_type,
                    status, title, content, content_key_id, retrieval, sensitivity, changed_at)
                VALUES (?, 1, 'UPDATED', 'USER', 1, 'identity', 'MEMORY', 'ACCEPTED', '옛 민감 제목', ?, ?, 'SEARCH',
                    'SENSITIVE', CURRENT_TIMESTAMP(6))
                """,
            legacyId, legacy.content(), legacy.keyId());
        Map<Long, String> before = Map.of(
            legacyId, Sha256.hex(reread(legacyId).content()), plainId, Sha256.hex(reread(plainId).content()));

        assertThat(migration.migrate()).isEqualTo(3);

        assertSealed(memoryRow(legacyId), SECOND);
        assertSealed(memoryRow(plainId), TITLE, FIRST);
        assertThat(revisionRows(legacyId)).singleElement().satisfies(row -> assertSealed(row, SECOND));
        before.forEach((id, hash) -> assertThat(Sha256.hex(reread(id).content())).isEqualTo(hash));
        assertThat(reread(legacyId).title()).isEqualTo("옛 민감 제목");
        assertThat(memories.revisionsOf(ADMIN, legacyId)).singleElement().extracting(MemoryRevision::content).isEqualTo(SECOND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory WHERE data_key_id IS NULL OR content_key_id IS NOT NULL", Long.class))
            .isZero();
        assertThat(migration.migrate()).isZero();
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("이관이 한 줄에서 실패해도 다른 줄은 옮기고, 실패한 줄은 옛 모양 그대로이며 로그에 번호만 남는다")
    void migrationFailureLeavesOnlyThatRow(CapturedOutput output) {
        Long broken = insertRow("USER", 1L, null, "깨진 옛 줄", "v1.깨진-표식-1188", "missing-key");
        Long plain = insertRow("USER", 1L, null, TITLE, FIRST, null);

        assertThat(migration.migrate()).isEqualTo(1);

        assertSealed(memoryRow(plain), TITLE, FIRST);
        assertThat(memoryRow(broken))
            .containsEntry("CONTENT", "v1.깨진-표식-1188")
            .containsEntry("CONTENT_KEY_ID", "missing-key")
            .containsEntry("DATA_KEY_ID", null);
        assertThat(output.getOut()).contains("memoryIds=[" + broken + "]").doesNotContain("깨진-표식-1188", FIRST);
    }


    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
