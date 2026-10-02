package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryContentBackfill;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.memory.presentation.MemoryController;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * key 가 없을 때 민감 본문이 평문으로 저장되는 길이 없는지 본다(ADR-055).
 *
 * <p>저장과 수정, 일반 항목을 민감으로 고치는 길, 제안을 받아들이는 길을 모두 지난다.
 */
@SpringBootTest(properties = {"assistant.memory.encryption.active-key-id=", "assistant.memory.encryption.keys="})
@ActiveProfiles("test")
class MemoryEncryptionDisabledTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@example.com", "admin", 1L, UserRole.ADMIN);
    private static final String PLAIN = "평문-표식-5520";

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    MemoryContentBackfill backfill;

    @Autowired
    ContextAssembler context;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // 설정이 다른 컨텍스트도 같은 메모리 데이터베이스를 쓴다. 비우지 않으면 줄 수 단언이 순서에 따라 흔들린다
        revisionRepository.deleteAll();
        repository.deleteAll();
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long insertSensitiveRow(String content, String keyId) {
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, collection, entry_type, title, content, content_key_id,
                    retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES ('USER', 1, 'identity', 'MEMORY', '직접 넣은 줄', ?, ?, 'SEARCH', FALSE, 'SENSITIVE', 1,
                    'ACCEPTED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, content, keyId);
        return jdbc.queryForObject("SELECT MAX(id) FROM memory", Long.class);
    }

    @Test
    @DisplayName("key 가 없으면 민감 항목을 만들지 못하고 아무것도 저장하지 않는다")
    void createSensitiveIsRejected() {
        assertUnavailable(() -> memories.create(
                ADMIN, MemoryScope.USER, "신원", PLAIN, "identity", MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE));

        assertThat(count("memory")).isZero();
        assertThat(count("memory_revision")).isZero();
    }

    @Test
    @DisplayName("key 가 없으면 그룹 범위의 민감 항목도 만들지 못한다")
    void createGroupSensitiveIsRejected() {
        assertUnavailable(() -> memories.create(
                ADMIN,
                MemoryScope.GROUP,
                "신원",
                PLAIN,
                "identity",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.SENSITIVE));

        assertThat(count("memory")).isZero();
    }

    @Test
    @DisplayName("일반 항목을 민감으로 고치려 하면 거절되고 본문과 민감도와 판이 그대로다")
    void normalToSensitiveIsRejected() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "일반", "처음 글", false);
        // 평문 판 1 을 먼저 둔다. 거절된 수정이 앞선 판도 건드리지 않는지 본다
        memories.update(ADMIN, memory.id(), PLAIN, false);

        assertUnavailable(
                () -> memories.update(ADMIN, memory.id(), PLAIN, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE));

        Memory current = repository.findById(memory.id()).orElseThrow();
        assertThat(current.content()).isEqualTo(PLAIN);
        assertThat(current.sensitivity()).isEqualTo(MemorySensitivity.NORMAL);
        assertThat(current.revision()).isEqualTo(2);
        assertThat(count("memory_revision")).isEqualTo(1);
        Map<String, Object> revision = jdbc.queryForMap(
                "SELECT content, content_key_id FROM memory_revision WHERE memory_id = ? AND revision = 1",
                memory.id());
        assertThat(revision.get("CONTENT")).isEqualTo("처음 글");
        assertThat(revision.get("CONTENT_KEY_ID")).isNull();
    }

    @Test
    @DisplayName("제안을 받아들여도 민감 항목이 되지 않고 민감으로 고치는 길은 거절된다")
    void acceptedProposalCannotBecomeSensitivePlain() {
        Memory proposed = memories.proposeUser(ADMIN, "제안", PLAIN, 1L);

        memories.accept(ADMIN, proposed.id());

        Memory accepted = repository.findById(proposed.id()).orElseThrow();
        assertThat(accepted.sensitivity()).isEqualTo(MemorySensitivity.NORMAL);
        assertUnavailable(() ->
                memories.update(ADMIN, proposed.id(), PLAIN, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE));
        assertThat(count("memory_revision")).isZero();
    }

    @Test
    @DisplayName("일반 항목은 key 가 없어도 만들고 고치고 지운다")
    void normalWorksWithoutKey() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "일반", PLAIN, false);

        memories.update(ADMIN, memory.id(), "고친 글", true);
        memories.delete(ADMIN, memory.id());

        assertThat(count("memory")).isZero();
        assertThat(count("memory_revision")).isEqualTo(2);
    }

    @Test
    @DisplayName("목록에 없는 key 로 암호화된 줄은 contentOf 가 거절하고 목록은 실패하지 않는다")
    void sealedRowWithoutKey() {
        long id = insertSensitiveRow("v1.AAAA.BBBB", "gone-1");

        assertUnavailable(() -> memories.contentOf(repository.findById(id).orElseThrow()));
        assertThat(views())
                .singleElement()
                .satisfies(view -> assertThat(view.content()).isEmpty());
    }

    @Test
    @DisplayName("평문으로 남은 민감 줄도 목록으로 나가지 않는다")
    void plainSensitiveRowIsNotListed() {
        insertSensitiveRow(PLAIN, null);

        assertThat(views())
                .singleElement()
                .satisfies(view -> assertThat(view.content()).isEmpty());
    }

    @Test
    @DisplayName("key 가 없으면 평문으로 남은 민감 줄을 옮기지 않고 그대로 둔다")
    void backfillLeavesPlainRowsWithoutKey() {
        long id = insertSensitiveRow(PLAIN, null);

        assertThat(backfill.sealPlaintext()).isZero();

        assertThat(jdbc.queryForObject("SELECT content FROM memory WHERE id = ?", String.class, id))
                .isEqualTo(PLAIN);
    }

    private List<MemoryView> views() {
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        Mockito.when(currentUser.require()).thenReturn(ADMIN);
        return new MemoryController(memories, context, currentUser).readable();
    }
}
