package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.context.ContextAssembler;
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

/** 민감 항목의 본문이 데이터베이스에 암호문으로만 남는지 본다(ADR-055). 데이터베이스는 JdbcTemplate 으로 직접 읽는다. */
@SpringBootTest
@ActiveProfiles("test")
class MemoryEncryptionTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@example.com", "admin", 1L, UserRole.ADMIN);
    private static final String FIRST = "평문-표식-7391";
    private static final String SECOND = "평문-표식-8802";

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    ContextAssembler context;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        revisionRepository.deleteAll();
        repository.deleteAll();
    }

    private Memory sensitive(MemoryScope scope, String content) {
        return memories.create(
                ADMIN, scope, "신원 문서", content, "identity", MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
    }

    private Map<String, Object> memoryRow(Long id) {
        return jdbc.queryForMap("SELECT content, content_key_id FROM memory WHERE id = ?", id);
    }

    @Test
    @DisplayName("민감 항목을 만들면 본문이 암호문으로 저장되고 contentOf 가 평문을 낸다")
    void createStoresCiphertext() {
        Memory memory = sensitive(MemoryScope.USER, FIRST);

        Map<String, Object> row = memoryRow(memory.id());
        assertThat((String) row.get("CONTENT")).startsWith("v1.").doesNotContain(FIRST);
        assertThat(row.get("CONTENT_KEY_ID")).isEqualTo("test-1");
        assertThat(memories.contentOf(repository.findById(memory.id()).orElseThrow()))
                .isEqualTo(FIRST);
    }

    @Test
    @DisplayName("고치면 물러난 판과 지금 줄이 모두 암호문이고 지우면 DELETED 판도 암호문이다")
    void updateAndDeleteKeepCiphertextOnly() {
        Memory memory = sensitive(MemoryScope.USER, FIRST);

        memories.update(ADMIN, memory.id(), SECOND, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);

        Map<String, Object> revision = jdbc.queryForMap(
                "SELECT content, content_key_id FROM memory_revision WHERE memory_id = ? AND revision = 1",
                memory.id());
        assertThat((String) revision.get("CONTENT")).doesNotContain(FIRST, SECOND);
        assertThat(revision.get("CONTENT_KEY_ID")).isEqualTo("test-1");
        assertThat((String) memoryRow(memory.id()).get("CONTENT")).doesNotContain(FIRST, SECOND);

        memories.delete(ADMIN, memory.id());

        String deleted = jdbc.queryForObject(
                "SELECT content FROM memory_revision WHERE memory_id = ? AND change_type = 'DELETED'",
                String.class,
                memory.id());
        assertThat(deleted).doesNotContain(FIRST, SECOND);
    }

    @Test
    @DisplayName("민감하지 않은 항목은 평문 그대로이고 content_key_id 가 비어 있다")
    void normalStaysPlain() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "일반", FIRST, false);

        Map<String, Object> row = memoryRow(memory.id());
        assertThat(row.get("CONTENT")).isEqualTo(FIRST);
        assertThat(row.get("CONTENT_KEY_ID")).isNull();
    }

    @Test
    @DisplayName("일반 항목을 민감으로 고치면 지금 줄은 암호문이고 물러난 판은 평문이다")
    void normalToSensitive() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "일반", FIRST, false);

        memories.update(ADMIN, memory.id(), FIRST, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);

        assertThat((String) memoryRow(memory.id()).get("CONTENT"))
                .startsWith("v1.")
                .doesNotContain(FIRST);
        Map<String, Object> revision = jdbc.queryForMap(
                "SELECT content, content_key_id FROM memory_revision WHERE memory_id = ? AND revision = 1",
                memory.id());
        assertThat(revision.get("CONTENT")).isEqualTo(FIRST);
        assertThat(revision.get("CONTENT_KEY_ID")).isNull();
    }

    @Test
    @DisplayName("민감 항목을 일반으로 고치면 지금 줄은 평문이고 물러난 판은 암호문이다")
    void sensitiveToNormal() {
        Memory memory = sensitive(MemoryScope.USER, FIRST);

        memories.update(ADMIN, memory.id(), FIRST, MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);

        Map<String, Object> row = memoryRow(memory.id());
        assertThat(row.get("CONTENT")).isEqualTo(FIRST);
        assertThat(row.get("CONTENT_KEY_ID")).isNull();
        String revision = jdbc.queryForObject(
                "SELECT content FROM memory_revision WHERE memory_id = ? AND revision = 1", String.class, memory.id());
        assertThat(revision).startsWith("v1.").doesNotContain(FIRST);
    }

    @Test
    @DisplayName("그룹 범위의 민감 항목도 암호화하고 contentOf 가 원래 글을 낸다")
    void groupScope() {
        Memory memory = sensitive(MemoryScope.GROUP, FIRST);

        assertThat((String) memoryRow(memory.id()).get("CONTENT")).doesNotContain(FIRST);
        assertThat(memories.contentOf(repository.findById(memory.id()).orElseThrow()))
                .isEqualTo(FIRST);
    }

    @Test
    @DisplayName("민감 항목은 목록에서 본문이 비어 있고 sensitive 가 참이다")
    void listOmitsSensitiveBody() {
        sensitive(MemoryScope.USER, FIRST);
        CurrentUserProvider currentUser = Mockito.mock(CurrentUserProvider.class);
        Mockito.when(currentUser.require()).thenReturn(ADMIN);
        MemoryController controller = new MemoryController(memories, context, currentUser);

        List<MemoryView> views = controller.readable();

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.content()).isEmpty();
            assertThat(view.sensitive()).isTrue();
        });
    }

    @Test
    @DisplayName("제안을 받아들여도 항목은 일반 평문이고 민감 항목이 되지 않는다")
    void acceptingProposalKeepsNormalPlain() {
        Memory proposed = memories.proposeUser(ADMIN, "제안", FIRST, 1L);

        memories.accept(ADMIN, proposed.id());

        Map<String, Object> row = memoryRow(proposed.id());
        assertThat(row.get("CONTENT_KEY_ID")).isNull();
        assertThat(repository.findById(proposed.id()).orElseThrow().sensitivity())
                .isEqualTo(MemorySensitivity.NORMAL);
    }

    @Test
    @DisplayName("민감 항목으로 고친 제안은 중복 키를 비워 본문의 지문을 남기지 않는다")
    void sensitiveRevisionClearsProposalDedupKey() {
        Memory proposed = memories.proposeUser(ADMIN, "제안", FIRST, 1L);
        memories.accept(ADMIN, proposed.id());

        memories.update(ADMIN, proposed.id(), SECOND, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);

        String key =
                jdbc.queryForObject("SELECT proposal_dedup_key FROM memory WHERE id = ?", String.class, proposed.id());
        assertThat(key).isNull();
    }
}
