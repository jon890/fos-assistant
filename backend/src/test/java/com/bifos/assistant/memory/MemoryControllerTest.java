package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.presentation.MemoryDtos.MemoryView;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.presentation.MemoryController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 사람이 제안을 승인하거나 거절하는 API 응답과 목록의 주입 여부 표시를 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class MemoryControllerTest {

    private static final CurrentUser ADMIN =
            new CurrentUser(1L, "admin@example.com", "admin", 1L, UserRole.ADMIN);

    @Autowired MemoryService memories;
    @Autowired MemoryRepository repository;
    @Autowired ContextAssembler context;
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private MemoryController controller;

    @BeforeEach
    void 준비한다() {
        repository.deleteAll();
        controller = new MemoryController(memories, context, currentUser);
        when(currentUser.require()).thenReturn(ADMIN);
    }

    @Test
    void 제안을_승인하고_거절한다() {
        Memory accepted = memories.proposeUser(ADMIN, "승인", "내용", 1L);
        Memory rejected = memories.proposeUser(ADMIN, "거절", "내용", 2L);

        assertThat(controller.accept(accepted.id()).status()).isEqualTo("ACCEPTED");
        assertThat(controller.reject(rejected.id()).status()).isEqualTo("REJECTED");
    }

    @Test
    void 자리가_없어_실리지_않는_항목에만_표시를_단다() {
        Memory tooLong = memories.create(ADMIN, MemoryScope.FAMILY, "너무 긴 항목", "가".repeat(9_000), true);
        Memory shortOne = memories.create(ADMIN, MemoryScope.FAMILY, "짧은 항목", "짧은 내용", true);

        assertThat(controller.readable())
                .filteredOn(MemoryView::omittedFromContext)
                .extracting(MemoryView::id)
                .containsExactly(tooLong.id());
        assertThat(controller.readable())
                .filteredOn(view -> view.id().equals(shortOne.id()))
                .allMatch(view -> !view.omittedFromContext());
    }
}
