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
import org.junit.jupiter.api.DisplayName;
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
    private static final CurrentUser OTHER_GROUP = user(3L, 20L, UserRole.ADMIN);

    @Autowired MemoryService memories;
    @Autowired MemoryRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("개인 항목은 주인만 보고 그룹 항목은 같은 그룹이 본다")
    void personalItemsOnlyOwnerSeesAndGroupItemsSameGroupSees() {
        Memory personal = memories.create(ADMIN, MemoryScope.USER, "개인", "내용", false);
        Memory group = memories.create(ADMIN, MemoryScope.GROUP, "그룹", "내용", false);

        assertThat(personal.groupId()).isNull();
        assertThat(memories.readableBy(ADMIN)).extracting(Memory::id).contains(personal.id(), group.id());
        assertThat(memories.readableBy(MEMBER)).extracting(Memory::id).containsExactly(group.id());
        assertThat(memories.readableBy(OTHER_GROUP)).isEmpty();
    }

    @Test
    @DisplayName("남의 개인 항목은 목록과 주입과 본문에서 모두 없다")
    void othersPersonalItemsAreAbsentFromListInjectionAndBody() {
        Memory personal = memories.create(ADMIN, MemoryScope.USER, "개인", "내용", true);

        assertThat(memories.readableBy(MEMBER)).isEmpty();
        assertThat(memories.alwaysInjectedFor(MEMBER)).isEmpty();
        assertThat(memories.indexedFor(MEMBER)).isEmpty();
        assertNotFound(() -> memories.bodyFor(MEMBER, personal.id()));
    }

    @Test
    @DisplayName("scope가 없으면 거절하고 항상 주입은 기본으로 켜지지 않는다")
    void rejectsMissingScopeAndAlwaysInjectionIsNotOnByDefault() {
        assertThatThrownBy(() -> memories.create(ADMIN, null, "제목", "내용", false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_SCOPE_REQUIRED));

        Memory memory = memories.create(ADMIN, MemoryScope.USER, "제목", "내용", false);
        assertThat(memory.alwaysInject()).isFalse();
        assertThat(memories.indexedFor(ADMIN)).extracting(Memory::id).contains(memory.id());
    }

    @Test
    @DisplayName("제안은 승인 전까지 주입과 본문에서 제외되고 승인 뒤 주입 층이 정해진다")
    void proposalIsExcludedUntilApprovedThenGetsInjectionLayer() {
        Memory proposed = memories.proposeUser(ADMIN, "제안", "내용", 99L);

        assertThat(proposed.scope()).isEqualTo(MemoryScope.USER);
        assertThat(proposed.groupId()).isNull();
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
    @DisplayName("MEMBER 역할은 그룹 항목을 만들거나 고치거나 지우지 못한다")
    void memberCannotCreateEditOrDeleteGroupItems() {
        Memory group = memories.create(ADMIN, MemoryScope.GROUP, "그룹", "내용", false);

        assertForbidden(() -> memories.create(MEMBER, MemoryScope.GROUP, "새 그룹", "내용", false));
        assertForbidden(() -> memories.update(MEMBER, group.id(), "수정", true));
        assertForbidden(() -> memories.delete(MEMBER, group.id()));
    }

    @Test
    @DisplayName("같은 제목과 본문을 제안하면 한 행만 남는다")
    void sameTitleAndBodyProposalLeavesOneRow() {
        Memory first = memories.proposeUser(ADMIN, "제안", "내용", 99L);
        memories.accept(ADMIN, first.id());
        memories.update(ADMIN, first.id(), "수정한 내용", false);
        Memory duplicate = memories.proposeUser(ADMIN, "제안", "수정한 내용", 100L);

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(repository.count()).isOne();
    }

    @Test
    @DisplayName("저장소도 동시에 들어온 중복 제안을 막는다")
    void repositoryAlsoBlocksConcurrentDuplicateProposals() {
        String key = "a".repeat(64);
        repository.saveAndFlush(Memory.proposedUser(ADMIN.id(), "제안", "내용", 99L, key));

        assertThatThrownBy(() -> repository.saveAndFlush(
                Memory.proposedUser(ADMIN.id(), "제안", "내용", 100L, key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static CurrentUser user(Long id, Long groupId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, groupId, role);
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
