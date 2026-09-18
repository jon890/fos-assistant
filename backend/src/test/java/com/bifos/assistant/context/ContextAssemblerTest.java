package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
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
    void 비운다() {
        repository.deleteAll();
    }

    @Test
    void 가족과_개인_항목을_층_순서대로_본문까지_넣는다() {
        memories.create(ADMIN, MemoryScope.FAMILY, "가족 제목", "가족 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains("# 우리 가족이 함께 아는 것", "가족 내용", "# 지금 묻는 사람에 대해 아는 것", "개인 내용")
                .containsSubsequence("# 우리 가족이 함께 아는 것", "가족 내용", "# 지금 묻는 사람에 대해 아는 것", "개인 내용");
        assertThat(result.chars()).isEqualTo(result.instructions().length());
    }

    @Test
    void 다른_구성원의_개인_항목과_승인_전_항목은_조립하지_않는다() {
        memories.create(ADMIN, MemoryScope.USER, "아빠 제목", "아빠만 아는 내용", true);
        memories.proposeUser(MEMBER, "제안 제목", "승인 전 내용", 1L);

        AssembledContext result = assembler.assemble(MEMBER);

        assertThat(result.instructions()).isNull();
        assertThat(result.chars()).isZero();
    }

    @Test
    void 넣을_항목이_없으면_null과_0을_낸다() {
        assertThat(assembler.assemble(ADMIN)).isEqualTo(AssembledContext.empty());
    }

    @Test
    void 한_층만_있으면_그_층의_제목만_넣는다() {
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions()).contains("# 지금 묻는 사람에 대해 아는 것", "개인 내용")
                .doesNotContain("# 우리 가족이 함께 아는 것", "# 더 물어볼 수 있는 것");
    }

    @Test
    void 상한을_넘는_항목은_일부도_넣지_않고_그_항목만_건너뛴다() {
        Memory first = memories.create(ADMIN, MemoryScope.FAMILY, "첫째", "가".repeat(5_000), true);
        Memory second = memories.create(ADMIN, MemoryScope.FAMILY, "둘째", "나".repeat(5_000), true);
        Memory third = memories.create(ADMIN, MemoryScope.FAMILY, "셋째", "짧은 내용", true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains(first.content(), third.content())
                .doesNotContain(second.content());
        assertThat(result.omittedMemoryIds()).containsExactly(second.id());
        assertThat(result.chars()).isEqualTo(result.instructions().length()).isLessThanOrEqualTo(8_000);
    }

    @Test
    void 첫_항목이_상한을_넘어도_색인과_나머지_항목을_싣는다() {
        Memory tooLong = memories.create(ADMIN, MemoryScope.FAMILY, "너무 긴 항목", "가".repeat(9_000), true);
        Memory shortOne = memories.create(ADMIN, MemoryScope.FAMILY, "짧은 항목", "짧은 내용", true);
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
    void 항상_층이_상한을_거의_채워도_색인_층을_싣는다() {
        memories.create(ADMIN, MemoryScope.FAMILY, "거의 상한", "가".repeat(7_960), true);
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인 제목", "색인 본문", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .isNotNull()
                .contains("# 더 물어볼 수 있는 것", "[" + indexed.id() + "] 색인 제목");
        assertThat(result.chars()).isLessThanOrEqualTo(8_000);
    }

    @Test
    void 색인이_짧으면_떼어_둔_자리를_항상_층이_쓴다() {
        Memory body = memories.create(ADMIN, MemoryScope.FAMILY, "본문", "가".repeat(7_000), true);
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인 제목", "색인 본문", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains(body.content(), "[" + indexed.id() + "] 색인 제목");
        assertThat(result.omittedItems()).isZero();
    }

    @Test
    void 모든_항목이_상한을_넘으면_비우고_빠진_수만_남긴다() {
        memories.create(ADMIN, MemoryScope.FAMILY, "첫째", "가".repeat(9_000), true);
        memories.create(ADMIN, MemoryScope.USER, "둘째", "나".repeat(9_000), true);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions()).isNull();
        assertThat(result.chars()).isZero();
        assertThat(result.omittedItems()).isEqualTo(2);
    }

    @Test
    void 상한_안에_다_들어가면_빠진_항목이_없다() {
        memories.create(ADMIN, MemoryScope.FAMILY, "가족 제목", "가족 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.omittedItems()).isZero();
        assertThat(result.omittedMemoryIds()).isEmpty();
    }

    @Test
    void 항상_층_뒤에_색인_층을_id_오름차순으로_넣는다() {
        Memory first = memories.create(ADMIN, MemoryScope.USER, "먼저 저장", "본문", false);
        Memory second = memories.create(ADMIN, MemoryScope.FAMILY, "나중 저장", "본문", false);

        AssembledContext result = assembler.assemble(ADMIN);

        assertThat(result.instructions())
                .contains("# 더 물어볼 수 있는 것", "[" + first.id() + "] 먼저 저장", "[" + second.id() + "] 나중 저장")
                .containsSubsequence("[" + first.id() + "] 먼저 저장", "[" + second.id() + "] 나중 저장");
    }

    private static CurrentUser user(Long id, Long familyId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, familyId, role);
    }
}
