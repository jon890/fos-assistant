package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 실행 instructions 에 들어갈 Memory 층과 권한 경계를 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class ContextAssemblerTest {

    private static final CurrentUser ADMIN = user(1L, 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = user(2L, 10L, UserRole.MEMBER);

    @Autowired ContextAssembler assembler;
    @Autowired MemoryService memories;
    @Autowired MemoryRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("그룹과 개인 항목을 층 순서대로 본문까지 넣는다")
    void assemblesGroupAndUserItemsInLayerOrderWithBodies() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 제목", "그룹 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains("# 우리 그룹이 함께 아는 것", "그룹 내용", "# 지금 묻는 사람에 대해 아는 것", "개인 내용")
                .containsSubsequence("# 우리 그룹이 함께 아는 것", "그룹 내용", "# 지금 묻는 사람에 대해 아는 것", "개인 내용");
        assertThat(result.chars()).isEqualTo(result.instructions().length());
    }

    @Test
    @DisplayName("다른 사용자의 개인 항목과 승인 전 항목은 조립하지 않는다")
    void skipsOtherUsersPersonalItemsAndUnapprovedItems() {
        memories.create(ADMIN, MemoryScope.USER, "아빠 제목", "아빠만 아는 내용", true);
        memories.proposeUser(MEMBER, "제안 제목", "승인 전 내용", 1L);

        AssembledContext result = assembler.assemble(MEMBER);

        assertThat(result.instructions()).isNull();
        assertThat(result.chars()).isZero();
    }

    @Test
    @DisplayName("넣을 항목이 없으면 null과 0을 낸다")
    void returnsNullAndZeroWhenNothingToInsert() {
        assertThat(assembler.assemble(ADMIN)).isEqualTo(AssembledContext.empty());
    }

    @Test
    @DisplayName("한 층만 있으면 그 층의 제목만 넣는다")
    void insertsOnlyTitlesOfLayerWhenOnlyOneLayerExists() {
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions()).contains("# 지금 묻는 사람에 대해 아는 것", "개인 내용")
                .doesNotContain("# 우리 그룹이 함께 아는 것", "# 더 물어볼 수 있는 것");
    }

    @Test
    @DisplayName("상한을 넘는 항목은 일부도 넣지 않고 그 항목만 건너뛴다")
    void skipsOnlyItemOverLimitWithoutPartialInsert() {
        Memory first = memories.create(ADMIN, MemoryScope.GROUP, "첫째", "가".repeat(5_000), true);
        Memory second = memories.create(ADMIN, MemoryScope.GROUP, "둘째", "나".repeat(5_000), true);
        Memory third = memories.create(ADMIN, MemoryScope.GROUP, "셋째", "짧은 내용", true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains(first.content(), third.content())
                .doesNotContain(second.content());
        assertThat(result.omittedMemoryIds()).containsExactly(second.id());
        assertThat(result.chars()).isEqualTo(result.instructions().length()).isLessThanOrEqualTo(8_000);
    }

    @Test
    @DisplayName("첫 항목이 상한을 넘어도 색인과 나머지 항목을 싣는다")
    void shipsIndexAndRestEvenIfFirstItemExceedsLimit() {
        Memory tooLong = memories.create(ADMIN, MemoryScope.GROUP, "너무 긴 항목", "가".repeat(9_000), true);
        Memory shortOne = memories.create(ADMIN, MemoryScope.GROUP, "짧은 항목", "짧은 내용", true);
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인만 하는 제목", "색인 본문", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .isNotNull()
                .contains("# 더 물어볼 수 있는 것", "[" + indexed.id() + "] 색인만 하는 제목", shortOne.content())
                .doesNotContain(tooLong.content());
        assertThat(result.omittedMemoryIds()).containsExactly(tooLong.id());
        assertThat(result.omittedItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("항상 층이 상한을 거의 채워도 색인 층을 싣는다")
    void shipsIndexLayerEvenWhenAlwaysLayerNearlyFillsLimit() {
        memories.create(ADMIN, MemoryScope.GROUP, "거의 상한", "가".repeat(7_960), true);
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인 제목", "색인 본문", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .isNotNull()
                .contains("# 더 물어볼 수 있는 것", "[" + indexed.id() + "] 색인 제목");
        assertThat(result.chars()).isLessThanOrEqualTo(8_000);
    }

    @Test
    @DisplayName("색인이 짧으면 떼어 둔 자리를 항상 층이 쓴다")
    void alwaysLayerUsesReservedSpaceWhenIndexIsShort() {
        Memory body = memories.create(ADMIN, MemoryScope.GROUP, "본문", "가".repeat(7_000), true);
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인 제목", "색인 본문", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains(body.content(), "[" + indexed.id() + "] 색인 제목");
        assertThat(result.omittedItems()).isZero();
    }

    @Test
    @DisplayName("모든 항목이 상한을 넘으면 비우고 빠진 수만 남긴다")
    void emptiesAndKeepsOnlyOmittedCountWhenAllItemsExceedLimit() {
        memories.create(ADMIN, MemoryScope.GROUP, "첫째", "가".repeat(9_000), true);
        memories.create(ADMIN, MemoryScope.USER, "둘째", "나".repeat(9_000), true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions()).isNull();
        assertThat(result.chars()).isZero();
        assertThat(result.omittedItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("상한 안에 다 들어가면 빠진 항목이 없다")
    void hasNoOmittedItemsWhenEverythingFitsLimit() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 제목", "그룹 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.omittedItems()).isZero();
        assertThat(result.omittedMemoryIds()).isEmpty();
    }

    @Test
    @DisplayName("항상 층 뒤에 색인 층을 id 오름차순으로 넣는다")
    void putsIndexLayerAfterAlwaysLayerInIdAscendingOrder() {
        Memory first = memories.create(ADMIN, MemoryScope.USER, "먼저 저장", "본문", false);
        Memory second = memories.create(ADMIN, MemoryScope.GROUP, "나중 저장", "본문", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains("# 더 물어볼 수 있는 것", "[" + first.id() + "] 먼저 저장", "[" + second.id() + "] 나중 저장")
                .containsSubsequence("[" + first.id() + "] 먼저 저장", "[" + second.id() + "] 나중 저장");
    }

    private static CurrentUser user(Long id, Long groupId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, groupId, role);
    }
}
