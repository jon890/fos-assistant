package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.memory.application.MemoryImportService;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportItemBody;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportRequest;
import com.bifos.assistant.memory.presentation.MemoryImportController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.type.UserRole;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 암호화 key 가 없을 때 민감 항목을 평문으로 저장하지 않는지 본다(ADR-058, ADR-055). */
@SpringBootTest(properties = {"assistant.memory.encryption.active-key-id=", "assistant.memory.encryption.keys="})
@ActiveProfiles("test")
class MemoryImportEncryptionDisabledTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    private static final LocalDate DATE = LocalDate.of(2026, 1, 2);

    @Autowired
    MemoryImportService imports;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    JdbcTemplate jdbc;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private MemoryImportController controller;

    @BeforeEach
    void setUp() {
        revisionRepository.deleteAll();
        repository.deleteAll();
        controller = new MemoryImportController(imports, currentUser);
        when(currentUser.require()).thenReturn(DAD);
    }

    private static ImportItemBody plain() {
        return new ImportItemBody(
                "private/wiki/sample/note-a.md", DATE, "core", "MEMORY", null, "일반 기억", "본문", false, "SEARCH");
    }

    private static ImportItemBody sensitive() {
        return new ImportItemBody(
                "private/wiki/sample/note-b.md",
                DATE,
                "career",
                "DOCUMENT",
                "notes",
                "민감 문서",
                "평문-표식-7391",
                true,
                "SEARCH");
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memory", Long.class);
    }

    @Test
    @DisplayName("key 가 없으면 민감 항목이 든 묶음을 통째로 거절하고 일반 항목도 저장하지 않는다")
    void rejectsWholeBundleWithSensitiveItem() {
        assertThatThrownBy(() -> controller.commit(new ImportRequest(1, List.of(plain(), sensitive()))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE));
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("미리보기도 같은 예외다")
    void previewRejectsToo() {
        assertThatThrownBy(() -> controller.preview(new ImportRequest(1, List.of(plain(), sensitive()))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE));
    }

    @Test
    @DisplayName("일반 항목만 있으면 key 가 없어도 저장한다")
    void plainItemsAreSaved() {
        assertThat(controller
                        .commit(new ImportRequest(1, List.of(plain())))
                        .getBody()
                        .newCount())
                .isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
    }
}
