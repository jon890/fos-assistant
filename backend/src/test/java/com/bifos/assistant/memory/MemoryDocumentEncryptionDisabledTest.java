package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MemoryEncryptionDisabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** key 가 없을 때 민감 문서가 평문으로 저장되는 길이 없는지 본다(ADR-055, ADR-057). */
@BackendIntegrationTest
@MemoryEncryptionDisabled
class MemoryDocumentEncryptionDisabledTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    private static final String MARK = "평문-표식-7391";

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

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE));
    }

    @Test
    @DisplayName("key 가 없으면 민감 문서를 만들지 못하고 아무것도 저장하지 않는다")
    void sensitiveDocumentIsNotCreated() {
        assertUnavailable(() -> memories.createDocument(
                DAD, "identity", "application-profile", "t", MARK, MemorySensitivity.SENSITIVE));

        assertThat(count("memory")).isZero();
    }

    @Test
    @DisplayName("key 가 없으면 일반 문서를 민감으로 고치지 못하고 평문과 판 번호가 그대로다")
    void documentCannotBecomeSensitive() {
        Memory created =
                memories.createDocument(DAD, "identity", "application-profile", "t", MARK, MemorySensitivity.NORMAL);

        assertUnavailable(() -> memories.reviseDocument(DAD, created.id(), "다른 글", MemorySensitivity.SENSITIVE, 1));

        assertThat(jdbc.queryForObject("SELECT content FROM memory WHERE id = ?", String.class, created.id()))
                .isEqualTo(MARK);
        assertThat(jdbc.queryForObject("SELECT revision FROM memory WHERE id = ?", Integer.class, created.id()))
                .isEqualTo(1);
        assertThat(count("memory_revision")).isZero();
    }

    @Test
    @DisplayName("key 가 없어도 일반 문서는 만들어진다")
    void normalDocumentIsCreated() {
        Memory created =
                memories.createDocument(DAD, "identity", "application-profile", "t", MARK, MemorySensitivity.NORMAL);

        assertThat(created.id()).isNotNull();
    }
}
