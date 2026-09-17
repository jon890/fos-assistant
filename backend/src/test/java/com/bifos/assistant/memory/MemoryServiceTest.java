package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.domain.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/** Memory 의 공개 범위, 승인 상태, 쓰기 권한을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class MemoryServiceTest {

    private static final CurrentUser ADMIN = user(1L, 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = user(2L, 10L, UserRole.MEMBER);
    private static final CurrentUser OTHER_FAMILY = user(3L, 20L, UserRole.ADMIN);

    @Autowired MemoryService memories;
    @Autowired MemoryRepository repository;

    @BeforeEach
    void 비운다() {
        repository.deleteAll();
    }

    @Test
    void 개인_항목은_주인만_보고_가족_항목은_같은_가구가_본다() {
        Memory personal = memories.create(ADMIN, MemoryScope.USER, "개인", "내용", false);
        Memory family = memories.create(ADMIN, MemoryScope.FAMILY, "가족", "내용", false);

        assertThat(personal.familyId()).isNull();
        assertThat(memories.readableBy(ADMIN)).extracting(Memory::id).contains(personal.id(), family.id());
        assertThat(memories.readableBy(MEMBER)).extracting(Memory::id).containsExactly(family.id());
        assertThat(memories.readableBy(OTHER_FAMILY)).isEmpty();
    }

    @Test
    void 남의_개인_항목은_목록과_주입과_본문에서_모두_없다() {
        Memory personal = memories.create(ADMIN, MemoryScope.USER, "개인", "내용", true);

        assertThat(memories.readableBy(MEMBER)).isEmpty();
        assertThat(memories.alwaysInjectedFor(MEMBER)).isEmpty();
        assertThat(memories.indexedFor(MEMBER)).isEmpty();
        assertNotFound(() -> memories.bodyFor(MEMBER, personal.id()));
    }

    @Test
    void scope가_없으면_거절하고_항상_주입은_기본으로_켜지지_않는다() {
        assertThatThrownBy(() -> memories.create(ADMIN, null, "제목", "내용", false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_SCOPE_REQUIRED));

        Memory memory = memories.create(ADMIN, MemoryScope.USER, "제목", "내용", false);
        assertThat(memory.alwaysInject()).isFalse();
        assertThat(memories.indexedFor(ADMIN)).extracting(Memory::id).contains(memory.id());
    }

    @Test
    void 제안은_승인_전까지_주입과_본문에서_제외되고_승인_뒤_주입_층이_정해진다() {
        Memory proposed = memories.proposeUser(ADMIN, "제안", "내용", 99L);

        assertThat(proposed.scope()).isEqualTo(MemoryScope.USER);
        assertThat(proposed.familyId()).isNull();
        assertThat(proposed.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(memories.alwaysInjectedFor(ADMIN)).isEmpty();
        assertThat(memories.indexedFor(ADMIN)).isEmpty();
        assertNotFound(() -> memories.bodyFor(ADMIN, proposed.id()));

        memories.update(ADMIN, proposed.id(), "내용", true);
        memories.accept(ADMIN, proposed.id());
        assertThat(memories.alwaysInjectedFor(ADMIN)).extracting(Memory::id).contains(proposed.id());
        assertThat(memories.indexedFor(ADMIN)).isEmpty();

        memories.update(ADMIN, proposed.id(), "내용", false);
        assertThat(memories.indexedFor(ADMIN)).extracting(Memory::id).contains(proposed.id());
        assertThat(memories.bodyFor(ADMIN, proposed.id()).content()).isEqualTo("내용");
    }

    @Test
    void 구성원은_가족_항목을_만들거나_고치거나_지우지_못한다() {
        Memory family = memories.create(ADMIN, MemoryScope.FAMILY, "가족", "내용", false);

        assertForbidden(() -> memories.create(MEMBER, MemoryScope.FAMILY, "새 가족", "내용", false));
        assertForbidden(() -> memories.update(MEMBER, family.id(), "수정", true));
        assertForbidden(() -> memories.delete(MEMBER, family.id()));
    }

    @Test
    void 같은_제목과_본문을_제안하면_한_행만_남는다() {
        Memory first = memories.proposeUser(ADMIN, "제안", "내용", 99L);
        memories.accept(ADMIN, first.id());
        Memory duplicate = memories.proposeUser(ADMIN, "제안", "내용", 100L);

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(repository.count()).isOne();
    }

    @Test
    void 저장소도_동시에_들어온_중복_제안을_막는다() {
        String key = "a".repeat(64);
        repository.saveAndFlush(Memory.proposedUser(ADMIN.id(), "제안", "내용", 99L, key));

        assertThatThrownBy(() -> repository.saveAndFlush(
                Memory.proposedUser(ADMIN.id(), "제안", "내용", 100L, key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static CurrentUser user(Long id, Long familyId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, familyId, role);
    }

    private static void assertNotFound(ThrowingAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
    }

    private static void assertForbidden(ThrowingAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
