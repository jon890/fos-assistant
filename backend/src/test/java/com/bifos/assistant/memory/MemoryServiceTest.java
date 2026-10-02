package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** Memory 의 공개 범위, 승인 상태, 쓰기 권한을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class MemoryServiceTest {

    private static final CurrentUser ADMIN = user(1L, 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = user(2L, 10L, UserRole.MEMBER);
    private static final CurrentUser OTHER_GROUP = user(3L, 20L, UserRole.ADMIN);

    /** core collection 만 받고 민감 항목은 받지 않는 에이전트의 실행이다. 표를 넓히기 전의 모든 실행과 같다. */
    private static final MemoryAccess CORE = MemoryAccess.of(Set.of("core"), Set.of());

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisionRepository;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        revisionRepository.deleteAll();
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
        assertThat(memories.alwaysInjectedFor(MEMBER, CORE)).isEmpty();
        assertThat(memories.indexedFor(MEMBER, CORE)).isEmpty();
        assertNotFound(() -> memories.bodyFor(MEMBER, CORE, personal.id()));
    }

    @Test
    @DisplayName("scope가 없으면 거절하고 항상 주입은 기본으로 켜지지 않는다")
    void rejectsMissingScopeAndAlwaysInjectionIsNotOnByDefault() {
        assertThatThrownBy(() -> memories.create(ADMIN, null, "제목", "내용", false))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_SCOPE_REQUIRED));

        Memory memory = memories.create(ADMIN, MemoryScope.USER, "제목", "내용", false);
        assertThat(memory.alwaysInject()).isFalse();
        assertThat(memories.indexedFor(ADMIN, CORE)).extracting(Memory::id).contains(memory.id());
    }

    @Test
    @DisplayName("제안은 승인 전까지 주입과 본문에서 제외되고 승인 뒤 주입 층이 정해진다")
    void proposalIsExcludedUntilApprovedThenGetsInjectionLayer() {
        Memory proposed = memories.proposeUser(ADMIN, "제안", "내용", 99L);

        assertThat(proposed.scope()).isEqualTo(MemoryScope.USER);
        assertThat(proposed.groupId()).isNull();
        assertThat(proposed.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(memories.alwaysInjectedFor(ADMIN, CORE)).isEmpty();
        assertThat(memories.indexedFor(ADMIN, CORE)).isEmpty();
        assertNotFound(() -> memories.bodyFor(ADMIN, CORE, proposed.id()));

        memories.update(ADMIN, proposed.id(), "내용", true);
        memories.accept(ADMIN, proposed.id());
        assertThat(memories.alwaysInjectedFor(ADMIN, CORE))
                .extracting(Memory::id)
                .contains(proposed.id());
        assertThat(memories.indexedFor(ADMIN, CORE)).isEmpty();

        memories.update(ADMIN, proposed.id(), "내용", false);
        assertThat(memories.indexedFor(ADMIN, CORE)).extracting(Memory::id).contains(proposed.id());
        assertThat(memories.bodyFor(ADMIN, CORE, proposed.id()).content()).isEqualTo("내용");
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
        repository.saveAndFlush(Memory.proposedUser(ADMIN.id(), "제안", "내용", 99L, key, NOW));

        assertThatThrownBy(() -> repository.saveAndFlush(Memory.proposedUser(ADMIN.id(), "제안", "내용", 100L, key, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("고치면 고치기 전의 값이 판으로 남고 지금 값의 판 번호가 하나 오른다")
    void updatingKeepsThePreviousValueAsARevision() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "제목", "처음 내용", false);
        assertThat(memory.revision()).isOne();

        memories.update(ADMIN, memory.id(), "둘째 내용", true);
        Memory current = memories.update(ADMIN, memory.id(), "셋째 내용", false);

        assertThat(current.revision()).isEqualTo(3);
        assertThat(current.content()).isEqualTo("셋째 내용");
        List<MemoryRevision> history = memories.revisionsOf(ADMIN, memory.id());
        assertThat(history).extracting(revision -> revision.id().revision()).containsExactly(1, 2);
        assertThat(history).extracting(MemoryRevision::content).containsExactly("처음 내용", "둘째 내용");
        assertThat(history)
                .extracting(MemoryRevision::retrieval)
                .containsExactly(MemoryRetrieval.SEARCH, MemoryRetrieval.ALWAYS);
        assertThat(history).allSatisfy(revision -> {
            assertThat(revision.changeType()).isEqualTo(MemoryChangeType.UPDATED);
            assertThat(revision.changedByUserId()).isEqualTo(ADMIN.id());
        });
    }

    @Test
    @DisplayName("지워도 판이 남고 마지막 값이 지운 판으로 더해진다")
    void deletingKeepsHistoryAndAddsATombstone() {
        Memory memory = memories.create(ADMIN, MemoryScope.GROUP, "제목", "처음 내용", false);
        memories.update(ADMIN, memory.id(), "고친 내용", false);

        memories.delete(ADMIN, memory.id());

        assertThat(repository.findById(memory.id())).isEmpty();
        List<MemoryRevision> history = memories.revisionsOf(ADMIN, memory.id());
        assertThat(history)
                .extracting(MemoryRevision::changeType)
                .containsExactly(MemoryChangeType.UPDATED, MemoryChangeType.DELETED);
        assertThat(history).extracting(MemoryRevision::content).containsExactly("처음 내용", "고친 내용");
        assertThat(history.getLast().id().revision()).isEqualTo(2);
        assertThat(history.getLast().status()).isEqualTo(MemoryStatus.ACCEPTED);
        assertThat(history.getLast().entryType()).isEqualTo(MemoryEntryType.MEMORY);
    }

    @Test
    @DisplayName("수정이 커밋되는 사이에 들어온 승인은 그 수정의 본문과 판 번호를 되돌리지 않는다")
    void acceptingWhileAnUpdateCommitsDoesNotRevertTheUpdate() throws Exception {
        Memory proposed = memories.proposeUser(ADMIN, "제안", "처음 내용", 99L);
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> accepting = pool.submit(() -> {
                locked.await();
                // 수정 트랜잭션이 줄을 잠근 동안 들어온다. 잠그지 않고 읽으면 처음 내용을 읽어 통째로 저장한다.
                return memories.accept(ADMIN, proposed.id());
            });
            transactions.executeWithoutResult(status -> {
                Memory held = repository.findByIdForUpdate(proposed.id()).orElseThrow();
                revisionRepository.save(MemoryRevision.of(held, MemoryChangeType.UPDATED, ADMIN.id(), null, NOW));
                held.revise(StoredContent.plain("고친 내용"), MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL, null, NOW);
                locked.countDown();
                // 승인 쪽이 읽기에 닿을 틈을 준다. 잠금이 있으면 이 커밋 뒤에야 읽는다.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
            });
            accepting.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Memory current = repository.findById(proposed.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(MemoryStatus.ACCEPTED);
        assertThat(current.content()).isEqualTo("고친 내용");
        assertThat(current.revision()).isEqualTo(2);
        assertThat(memories.revisionsOf(ADMIN, proposed.id()))
                .singleElement()
                .satisfies(revision -> assertThat(revision.content()).isEqualTo("처음 내용"));
    }

    @Test
    @DisplayName("같은 번호의 판을 다시 넣으면 덮어쓰지 않고 실패한다")
    void savingARevisionWithAnExistingNumberFailsInsteadOfOverwriting() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "제목", "처음 내용", false);
        revisionRepository.saveAndFlush(MemoryRevision.of(memory, MemoryChangeType.UPDATED, ADMIN.id(), null, NOW));

        assertThatThrownBy(() -> revisionRepository.saveAndFlush(
                        MemoryRevision.of(memory, MemoryChangeType.DELETED, ADMIN.id(), null, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(memories.revisionsOf(ADMIN, memory.id()))
                .singleElement()
                .satisfies(revision -> assertThat(revision.changeType()).isEqualTo(MemoryChangeType.UPDATED));
    }

    @Test
    @DisplayName("승인 전인 제안을 지우면 제안이었다는 것이 판에 남는다")
    void deletingAProposalKeepsItsStatusInTheTombstone() {
        Memory proposed = memories.proposeUser(ADMIN, "제안", "내용", 99L);

        memories.delete(ADMIN, proposed.id());

        assertThat(memories.revisionsOf(ADMIN, proposed.id())).singleElement().satisfies(revision -> {
            assertThat(revision.changeType()).isEqualTo(MemoryChangeType.DELETED);
            assertThat(revision.status()).isEqualTo(MemoryStatus.PROPOSED);
        });
    }

    @Test
    @DisplayName("그룹이 없는 사용자는 그룹이 비어 있는 그룹 항목을 보지 못한다")
    void usersWithoutAGroupDoNotMatchGroupItemsWithoutAGroup() {
        Memory orphan = repository.save(Memory.accepted(
                MemoryScope.GROUP,
                null,
                null,
                "주인 없는 그룹 항목",
                StoredContent.plain("내용"),
                MemoryPlacement.core(MemoryRetrieval.SEARCH),
                1L,
                NOW));
        CurrentUser noGroup = user(9L, null, UserRole.ADMIN);

        assertThat(memories.readableBy(noGroup)).isEmpty();
        assertNotFound(() -> memories.bodyFor(noGroup, CORE, orphan.id()));
        assertNotFound(() -> memories.update(noGroup, orphan.id(), "고침", false));
        assertNotFound(() -> memories.delete(noGroup, orphan.id()));
    }

    @Test
    @DisplayName("남의 개인 항목의 판과 다른 그룹 항목의 판은 없는 항목과 같게 비어 있다")
    void revisionsOfOthersItemsLookLikeMissingItems() {
        Memory personal = memories.create(ADMIN, MemoryScope.USER, "개인", "처음", false);
        Memory group = memories.create(ADMIN, MemoryScope.GROUP, "그룹", "처음", false);
        memories.update(ADMIN, personal.id(), "고침", false);
        memories.delete(ADMIN, group.id());

        assertThat(memories.revisionsOf(MEMBER, personal.id())).isEmpty();
        assertThat(memories.revisionsOf(OTHER_GROUP, group.id())).isEmpty();
        assertThat(memories.revisionsOf(MEMBER, group.id())).hasSize(1);
        assertThat(memories.revisionsOf(ADMIN, 999_999L)).isEmpty();
    }

    @Test
    @DisplayName("민감 항목은 만들 때도 고칠 때도 항상 싣게 둘 수 없다")
    void sensitiveItemsCannotAlwaysBeInjected() {
        assertSensitiveAlways(() -> memories.create(
                ADMIN, MemoryScope.USER, "민감", "내용", "identity", MemoryRetrieval.ALWAYS, MemorySensitivity.SENSITIVE));

        Memory sensitive = memories.create(
                ADMIN, MemoryScope.USER, "민감", "내용", "identity", MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
        assertThatThrownBy(() -> memories.update(ADMIN, sensitive.id(), "내용", true))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_SENSITIVE_NOT_EDITABLE));
        assertSensitiveAlways(() ->
                memories.update(ADMIN, sensitive.id(), "내용", MemoryRetrieval.ALWAYS, MemorySensitivity.SENSITIVE));

        Memory always = memories.create(ADMIN, MemoryScope.USER, "항상", "내용", true);
        assertSensitiveAlways(
                () -> memories.update(ADMIN, always.id(), "내용", MemoryRetrieval.ALWAYS, MemorySensitivity.SENSITIVE));
        // 거절한 수정은 판을 남기지 않는다.
        assertThat(memories.revisionsOf(ADMIN, sensitive.id())).isEmpty();
        assertThat(repository.findById(sensitive.id()).orElseThrow().revision()).isOne();
    }

    @Test
    @DisplayName("에이전트가 받지 않는 collection 의 항목은 주입과 색인에 없고 본문은 없는 항목과 같다")
    void itemsOutsideTheAgentsCollectionsAreAbsentEverywhere() {
        Memory core = memories.create(ADMIN, MemoryScope.USER, "기본", "기본 본문", false);
        Memory career = memories.create(
                ADMIN, MemoryScope.USER, "커리어", "커리어 본문", "career", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        Memory careerAlways = memories.create(
                ADMIN, MemoryScope.USER, "커리어 항상", "항상 본문", "career", MemoryRetrieval.ALWAYS, MemorySensitivity.NORMAL);
        MemoryAccess careerAgent = MemoryAccess.of(Set.of("core", "career"), Set.of());

        assertThat(memories.indexedFor(ADMIN, CORE)).extracting(Memory::id).containsExactly(core.id());
        assertThat(memories.alwaysInjectedFor(ADMIN, CORE)).isEmpty();
        assertNotFound(() -> memories.bodyFor(ADMIN, CORE, career.id()));

        assertThat(memories.indexedFor(ADMIN, careerAgent))
                .extracting(Memory::id)
                .containsExactly(core.id(), career.id());
        assertThat(memories.alwaysInjectedFor(ADMIN, careerAgent))
                .extracting(Memory::id)
                .containsExactly(careerAlways.id());
        assertThat(memories.bodyFor(ADMIN, careerAgent, career.id()).content()).isEqualTo("커리어 본문");
    }

    @Test
    @DisplayName("아무 collection 도 받지 않는 실행은 본인 항목도 받지 못한다")
    void runsWithoutAnyCollectionReceiveNothing() {
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인", "본문", false);
        memories.create(ADMIN, MemoryScope.USER, "항상", "본문", true);

        assertThat(memories.alwaysInjectedFor(ADMIN, MemoryAccess.none())).isEmpty();
        assertThat(memories.indexedFor(ADMIN, MemoryAccess.none())).isEmpty();
        assertNotFound(() -> memories.bodyFor(ADMIN, MemoryAccess.none(), indexed.id()));
    }

    @Test
    @DisplayName("민감 항목은 그 collection 에서 허용받은 실행만 색인과 본문으로 받는다")
    void sensitiveItemsNeedAnExplicitAllowance() {
        Memory sensitive = memories.create(
                ADMIN,
                MemoryScope.USER,
                "신원",
                "민감 본문",
                "identity",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.SENSITIVE);
        Memory normal = memories.create(
                ADMIN, MemoryScope.USER, "별명", "보통 본문", "identity", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        MemoryAccess withoutSensitive = MemoryAccess.of(Set.of("identity"), Set.of());
        MemoryAccess withSensitive = MemoryAccess.of(Set.of("identity"), Set.of("identity"));
        // 다른 collection 의 민감 허용은 이 collection 에 통하지 않는다.
        MemoryAccess otherSensitive = MemoryAccess.of(Set.of("identity", "career"), Set.of("career"));

        assertThat(memories.indexedFor(ADMIN, withoutSensitive))
                .extracting(Memory::id)
                .containsExactly(normal.id());
        assertNotFound(() -> memories.bodyFor(ADMIN, withoutSensitive, sensitive.id()));
        assertThat(memories.indexedFor(ADMIN, otherSensitive))
                .extracting(Memory::id)
                .containsExactly(normal.id());
        assertNotFound(() -> memories.bodyFor(ADMIN, otherSensitive, sensitive.id()));

        assertThat(memories.indexedFor(ADMIN, withSensitive))
                .extracting(Memory::id)
                .containsExactly(sensitive.id(), normal.id());
        assertThat(memories.contentOf(memories.bodyFor(ADMIN, withSensitive, sensitive.id())))
                .isEqualTo("민감 본문");
    }

    @Test
    @DisplayName("보관한 항목은 색인에 없고 본문도 읽지 못하며 옛 화면의 수정이 보관을 풀지 않는다")
    void archivedItemsStayOutOfTheIndex() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "옛 사실", "본문", false);
        memories.update(ADMIN, memory.id(), "본문", MemoryRetrieval.ARCHIVE, MemorySensitivity.NORMAL);

        assertThat(memories.indexedFor(ADMIN, CORE)).isEmpty();
        assertThat(memories.alwaysInjectedFor(ADMIN, CORE)).isEmpty();
        assertNotFound(() -> memories.bodyFor(ADMIN, CORE, memory.id()));
        assertThat(memories.readableBy(ADMIN)).extracting(Memory::id).containsExactly(memory.id());

        Memory edited = memories.update(ADMIN, memory.id(), "고친 본문", false);
        assertThat(edited.retrieval()).isEqualTo(MemoryRetrieval.ARCHIVE);
        assertThat(edited.alwaysInject()).isFalse();
    }

    @Test
    @DisplayName("옛 칸 always inject 는 꺼내는 방식과 늘 같은 뜻으로 적힌다")
    void alwaysInjectColumnFollowsRetrieval() {
        Memory memory = memories.create(ADMIN, MemoryScope.USER, "제목", "내용", true);
        assertThat(memory.retrieval()).isEqualTo(MemoryRetrieval.ALWAYS);
        assertThat(memory.alwaysInject()).isTrue();
        assertThat(memory.collection()).isEqualTo("core");
        assertThat(memory.sensitivity()).isEqualTo(MemorySensitivity.NORMAL);

        Memory changed = memories.update(ADMIN, memory.id(), "내용", false);
        assertThat(changed.retrieval()).isEqualTo(MemoryRetrieval.SEARCH);
        assertThat(changed.alwaysInject()).isFalse();
    }

    @Test
    @DisplayName("collection key 가 형식에 맞지 않으면 만들지 못한다")
    void rejectsMalformedCollectionKeys() {
        for (String key : List.of("", "Core", "커리어", "a b", "-core")) {
            assertThatThrownBy(() -> memories.create(
                            ADMIN, MemoryScope.USER, "제목", "내용", key, MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL))
                    .isInstanceOfSatisfying(
                            ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    private static CurrentUser user(Long id, Long groupId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, groupId, role);
    }

    private static void assertNotFound(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
    }

    private static void assertForbidden(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static void assertSensitiveAlways(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_SENSITIVE_ALWAYS));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
