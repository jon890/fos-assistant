package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryCaptureService;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.MemorySources;
import com.bifos.assistant.memory.application.MemoryTextMigration;
import com.bifos.assistant.memory.application.MemoryTexts;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.application.dto.MemoryRememberOutcome;
import com.bifos.assistant.memory.application.dto.MemoryRememberRequest;
import com.bifos.assistant.memory.application.dto.MemoryRememberResult;
import com.bifos.assistant.memory.domain.MemoryEmbeddingVector;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
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
    private static final MemoryAccess CORE = MemoryAccess.of(Set.of("core"));
    private static final AtomicLong EXECUTIONS = new AtomicLong(980_000L);

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryTexts texts;

    @Autowired
    Environment environment;

    @Autowired
    MemoryTextMigration migration;

    @Autowired
    MemoryCaptureService captures;

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
        return jdbc.queryForMap("SELECT title, content, data_key_id FROM memory WHERE id = ?", id);
    }


    private List<Map<String, Object>> revisionRows(Long memoryId) {
        return jdbc.queryForList(
            "SELECT title, content, data_key_id FROM memory_revision WHERE memory_id = ? ORDER BY revision",
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
    }


    private Memory reread(Long id) {
        return repository.findById(id).orElseThrow();
    }


    private List<MemoryView> listOf(CurrentUser user) {
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        Mockito.when(currentUser.require()).thenReturn(user);
        return new MemoryController(memories, context, sources, linkService, currentUser).readable();
    }


    /** 직전 이미지가 남긴 것처럼 평문 줄을 넣는다. */
    private Long insertRow(String scope, Long owner, Long group, String title, String content) {
        jdbc.update(
            """
                INSERT INTO memory (scope, owner_user_id, group_id, collection, entry_type, title, content,
                    retrieval, always_inject, revision, status, created_at, updated_at)
                VALUES (?, ?, ?, 'core', 'MEMORY', ?, ?, 'SEARCH', FALSE, 1, 'ACCEPTED',
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
            scope, owner, group, title, content);
        return jdbc.queryForObject("SELECT MAX(id) FROM memory", Long.class);
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("옛 key 설정 없이 뜬 서버에서 화면, 그룹, 문서, 제안으로 만든 기억은 제목과 본문이 모두 데이터 key 의 암호문이고 다시 읽으면 원래 글이다")
    void everyCreatePathStoresCiphertext() {
        assertThat(environment.getProperty("assistant.memory.encryption.keys")).isNull();
        assertThat(environment.getProperty("assistant.memory.encryption.active-key-id")).isNull();
        Memory normal = memories.create(ADMIN, MemoryScope.USER, TITLE, FIRST, false);
        Memory group = memories.create(ADMIN, MemoryScope.GROUP, TITLE, FIRST, "core", MemoryRetrieval.SEARCH);
        Memory document = memories.createDocument(ADMIN, "core", "doc-1", TITLE, FIRST);
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
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("에이전트의 바로 저장과 제안도 제목과 본문을 데이터 key 의 암호문으로 남긴다")
    void rememberPathsStoreCiphertext() {
        MemoryRememberResult direct = captures.remember(ADMIN, CORE, remember(TITLE, FIRST, true));
        MemoryRememberResult proposed = captures.remember(ADMIN, CORE, remember("제안 " + TITLE, SECOND, false));

        assertThat(direct.outcome()).isEqualTo(MemoryRememberOutcome.REMEMBERED);
        assertThat(proposed.outcome()).isEqualTo(MemoryRememberOutcome.PROPOSED);
        assertSealed(memoryRow(direct.memoryId()), TITLE, FIRST);
        assertSealed(memoryRow(proposed.memoryId()), TITLE, SECOND);
        assertThat(reread(direct.memoryId()).content()).isEqualTo(FIRST);
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
        Memory group = memories.create(
            ADMIN, MemoryScope.GROUP, "그룹 제목", SECOND, "core", MemoryRetrieval.SEARCH);
        jdbc.update("UPDATE memory SET group_id = 2 WHERE id = ?", group.id());

        assertThat(repository.findById(group.id()).orElseThrow().readable()).as("다른 그룹으로 옮긴 그룹 기억").isFalse();
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
        assertThat(memories.alwaysInjectedFor(ADMIN, MemoryAccess.of(Set.of("core"))))
            .extracting(Memory::id)
            .containsExactly(readable.id());
        assertCode(() -> memories.update(ADMIN, broken.id(), "덮어쓰기", false, null), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        assertCode(() -> memories.contentOf(reread(broken.id())), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        memories.delete(ADMIN, broken.id());
        assertThat(repository.findById(broken.id())).isEmpty();
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("KEK 가 있는데 주인 데이터 key 를 풀지 못하면 저장과 고치기를 거절하고 줄과 revision 이 그대로이며 그 사용자의 목록은 실패하지 않는다")
    void unusableDataKeyRejectsSave() {
        CurrentUser locked = new CurrentUser(4242L, "locked@example.com", "locked", 42L, UserRole.ADMIN);
        jdbc.update(
            "INSERT INTO user_data_key (user_id, kek_id, wrapped_key, created_at) VALUES (4242, 'missing-kek', 'v1.x.y', CURRENT_TIMESTAMP(6))");
        Long lockedKeyId = jdbc.queryForObject("SELECT id FROM user_data_key WHERE user_id = 4242", Long.class);
        Long plain = insertRow("USER", 4242L, null, "옮기기 전 제목", "옮기기 전 본문");
        jdbc.update("UPDATE memory SET collection = 'core' WHERE id = ?", plain);
        Long sealed = insertRow("USER", 4242L, null, "v1.AAAAAAAAAAAAAAAA.AAAA", "v1.AAAAAAAAAAAAAAAA.AAAA");
        jdbc.update("UPDATE memory SET data_key_id = ? WHERE id = ?", lockedKeyId, sealed);
        long before = repository.count();

        assertCode(() -> memories.create(locked, MemoryScope.USER, TITLE, FIRST, false), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        assertCode(() -> memories.proposeUser(locked, TITLE, FIRST, 1L), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        assertCode(() -> memories.update(locked, plain, SECOND, false, null), ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE);
        assertThat(captures.remember(locked, CORE, remember(TITLE, FIRST, true)).outcome())
            .isEqualTo(MemoryRememberOutcome.ENCRYPTION_UNAVAILABLE);

        assertThat(repository.count()).isEqualTo(before);
        assertThat(memoryRow(plain))
            .containsEntry("TITLE", "옮기기 전 제목")
            .containsEntry("CONTENT", "옮기기 전 본문")
            .containsEntry("DATA_KEY_ID", null);
        assertThat(revisionRows(plain)).isEmpty();
        assertThat(listOf(locked))
            .filteredOn(view -> view.id().equals(sealed))
            .singleElement()
            .satisfies(view -> {
                assertThat(view.unreadable()).isTrue();
                assertThat(view.title()).isEqualTo(Memory.UNREADABLE_TITLE);
            });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory WHERE content LIKE ?", Long.class, "%" + FIRST + "%"))
            .isZero();
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("이관한 옛 줄도 목록에 본문이 보이고 목록 경로로 고치며 받은 collection 의 에이전트에 실린다")
    void migratedRowsAreOrdinary() {
        Long former = insertRow("USER", ADMIN.id(), null, "옛 제목", FIRST);
        assertThat(migration.migrate()).isEqualTo(1);

        assertThat(listOf(ADMIN)).singleElement().satisfies(view -> {
            assertThat(view.title()).isEqualTo("옛 제목");
            assertThat(view.content()).isEqualTo(FIRST);
            assertThat(view.unreadable()).isFalse();
        });
        memories.update(ADMIN, former, SECOND, false, null);
        assertThat(reread(former).content()).isEqualTo(SECOND);
        assertThat(memories.indexedFor(ADMIN, CORE)).extracting(Memory::id).containsExactly(former);
        assertThat(memories.bodyFor(ADMIN, CORE, former).content()).isEqualTo(SECOND);
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("기동 이관은 평문 줄과 revision 을 데이터 key 로 다시 쓰고 글의 해시가 같으며 두 번 돌려도 바뀌지 않는다")
    void migrationRewritesPlainRows() {
        Long userId = insertRow("USER", 1L, null, "옛 제목", SECOND);
        Long groupId = insertRow("GROUP", null, 1L, TITLE, FIRST);
        jdbc.update(
            """
                INSERT INTO memory_revision (memory_id, revision, change_type, scope, owner_user_id, collection, entry_type,
                    status, title, content, retrieval, changed_at)
                VALUES (?, 1, 'UPDATED', 'USER', 1, 'core', 'MEMORY', 'ACCEPTED', '옛 제목', ?, 'SEARCH', CURRENT_TIMESTAMP(6))
                """,
            userId, SECOND);
        Map<Long, String> before = Map.of(
            userId, Sha256.hex(reread(userId).content()), groupId, Sha256.hex(reread(groupId).content()));

        assertThat(migration.migrate()).isEqualTo(3);

        assertSealed(memoryRow(userId), SECOND);
        assertSealed(memoryRow(groupId), TITLE, FIRST);
        assertThat(revisionRows(userId)).singleElement().satisfies(row -> assertSealed(row, SECOND));
        before.forEach((id, hash) -> assertThat(Sha256.hex(reread(id).content())).isEqualTo(hash));
        assertThat(reread(userId).title()).isEqualTo("옛 제목");
        assertThat(memories.revisionsOf(ADMIN, userId)).singleElement().extracting(MemoryRevision::content).isEqualTo(SECOND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory WHERE data_key_id IS NULL", Long.class)).isZero();
        assertThat(migration.migrate()).isZero();
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("이관이 한 줄에서 실패해도 다른 줄은 옮기고, 실패한 줄은 옛 모양 그대로이며 로그에 번호만 남는다")
    void migrationFailureLeavesOnlyThatRow(CapturedOutput output) {
        // 주인이 빈 개인 기억은 주인 key 를 고를 수 없어 옮기지 못한다
        Long broken = insertRow("USER", null, null, "깨진 줄", "깨진-표식-1188");
        Long plain = insertRow("USER", 1L, null, TITLE, FIRST);

        assertThat(migration.migrate()).isEqualTo(1);

        assertSealed(memoryRow(plain), TITLE, FIRST);
        assertThat(memoryRow(broken))
            .containsEntry("CONTENT", "깨진-표식-1188")
            .containsEntry("DATA_KEY_ID", null);
        assertThat(output.getOut()).contains("memoryIds=[" + broken + "]").doesNotContain("깨진-표식-1188", FIRST);
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("제안 중복 키는 평문의 SHA-256 이 아니라 주인 key 의 HMAC 이고 같은 글이면 같은 값이며 다른 사용자면 다르다")
    void proposalDedupKeyIsOwnerHmac() {
        Memory proposed = memories.proposeUser(ADMIN, TITLE, FIRST, 1L);
        String stored = jdbc.queryForObject("SELECT proposal_dedup_key FROM memory WHERE id = ?", String.class, proposed.id());

        assertThat(stored).hasSize(64).isNotEqualTo(Sha256.hex(ADMIN.id() + "\u0000" + TITLE + "\u0000" + FIRST));
        assertThat(texts.proposalDedupKey(ADMIN.id(), TITLE, FIRST)).isEqualTo(stored);
        assertThat(memories.proposeUser(ADMIN, TITLE, FIRST, 2L).id()).as("같은 글의 제안은 다시 만들지 않는다").isEqualTo(proposed.id());
        assertThat(texts.proposalDedupKey(ADMIN.id() + 1, TITLE, FIRST)).isNotEqualTo(stored);
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("기동 이관은 평문 SHA-256 이던 제안 중복 키와 임베딩 지문을 주인 key 의 HMAC 으로 바꾸고 벡터는 그대로 두며 두 번 돌려도 바뀌지 않는다")
    void rekeyHashesReplacesPlainHashes() {
        Memory proposed = memories.proposeUser(ADMIN, TITLE, FIRST, 1L);
        String plainKey = Sha256.hex(ADMIN.id() + "\u0000" + TITLE + "\u0000" + FIRST);
        jdbc.update("UPDATE memory SET proposal_dedup_key = ? WHERE id = ?", plainKey, proposed.id());
        String text = TITLE + "\n" + FIRST;
        byte[] plainFingerprint = Sha256.newDigest().digest(("test-model\n" + text).getBytes(StandardCharsets.UTF_8));
        MemoryEmbeddingRows.write(jdbc, proposed.id(), "test-model", plainFingerprint, new float[] {1f});
        Memory stale = memories.create(ADMIN, MemoryScope.USER, "낡은 지문", SECOND, false);
        MemoryEmbeddingRows.write(jdbc, stale.id(), "test-model", new byte[32], new float[] {1f});

        assertThat(migration.rekeyHashes()).isEqualTo(3);

        assertThat(jdbc.queryForObject("SELECT proposal_dedup_key FROM memory WHERE id = ?", String.class, proposed.id()))
            .isEqualTo(texts.proposalDedupKey(ADMIN.id(), TITLE, FIRST))
            .isNotEqualTo(plainKey);
        Map<String, Object> vector = jdbc.queryForMap(
            "SELECT embedding_fingerprint, embedding_vector FROM memory WHERE id = ?", proposed.id());
        assertThat((byte[])vector.get("EMBEDDING_FINGERPRINT"))
            .isEqualTo(texts.embeddingFingerprint(MemoryScope.USER, ADMIN.id(), "test-model", text).orElseThrow())
            .isNotEqualTo(plainFingerprint);
        assertThat((byte[])vector.get("EMBEDDING_VECTOR")).isEqualTo(MemoryEmbeddingVector.encode(new float[] {1f}));
        assertThat(jdbc.queryForObject("SELECT embedding_fingerprint FROM memory WHERE id = ?", byte[].class, stale.id()))
            .as("지금 글과 맞지 않는 지문은 벡터째 비워 다시 채우게 한다")
            .isNull();
        assertThat(migration.rekeyHashes()).isZero();
    }


    @Test
    @MysqlCase(MysqlCase.Reason.MIGRATION_DDL)
    @DisplayName("같은 글의 제안이 이미 HMAC 키를 가졌으면 SHA-256 키로 남은 줄은 키를 비워 평문 해시를 남기지 않는다")
    void rekeyClearsCollidingDedupKey() {
        Memory first = memories.proposeUser(ADMIN, TITLE, FIRST, 1L);
        // 직전 이미지로 되돌린 동안 같은 글의 제안이 SHA-256 키로 하나 더 생겼다
        String plainKey = Sha256.hex(ADMIN.id() + "\u0000" + TITLE + "\u0000" + FIRST);
        Long second = insertRow("USER", ADMIN.id(), null, TITLE, FIRST);
        jdbc.update("UPDATE memory SET status = 'PROPOSED', proposal_dedup_key = ? WHERE id = ?", plainKey, second);
        assertThat(migration.migrate()).isEqualTo(1);

        assertThat(migration.rekeyHashes()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT proposal_dedup_key FROM memory WHERE id = ?", String.class, second)).isNull();
        assertThat(jdbc.queryForObject("SELECT proposal_dedup_key FROM memory WHERE id = ?", String.class, first.id()))
            .isEqualTo(texts.proposalDedupKey(ADMIN.id(), TITLE, FIRST));
        assertThat(migration.rekeyHashes()).isZero();
    }


    private static MemoryRememberRequest remember(String title, String content, boolean direct) {
        // 실행마다 남기는 수에 상한이 있어 호출마다 다른 실행 번호를 쓴다
        return new MemoryRememberRequest(
            title, content, null, false, null, EXECUTIONS.incrementAndGet(), null, direct, false, false);
    }


    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
