package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.MemorySummaryBackfill;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 배포 때 이관 전 기억에 요약을 처음 적는 기동 이관을 확인한다(docs/features/memory/spec.md 의 REQ8). */
@BackendIntegrationTest
class MemorySummaryBackfillTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@example.com", "admin", 1L, UserRole.ADMIN);

    @Autowired
    MemorySummaryBackfill backfill;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryRevisionRepository revisions;

    @Autowired
    ContextAssembler assembler;

    @Autowired
    JdbcTemplate jdbc;


    @BeforeEach
    void clean() {
        revisions.deleteAll();
        repository.deleteAll();
    }


    /** 직전 이미지가 남긴 줄처럼 요약 칸을 이관 전으로 되돌린다. */
    private Memory pending(Memory memory) {
        jdbc.update("UPDATE memory SET summary = NULL WHERE id = ?", memory.id());
        return memory;
    }


    private Memory reread(Memory memory) {
        return repository.findById(memory.id()).orElseThrow();
    }


    @Test
    @DisplayName("짧은 개인 사실은 요약 = 본문이고, 긴 본문과 보관, 대체됨, 그룹, 문서, 제안은 빈 글이며 두 번 돌아도 같다")
    void fillsTargetsAndEmptiesOthers() {
        Memory shortFact = pending(memories.create(ADMIN, MemoryScope.USER, "짧은 사실", "짧은 본문", false));
        Memory atLimit = pending(memories.create(ADMIN, MemoryScope.USER, "상한 사실", "나".repeat(200), false));
        Memory longFact = pending(memories.create(ADMIN, MemoryScope.USER, "긴 사실", "다".repeat(201), false));
        Memory archived = pending(memories.create(
            ADMIN, MemoryScope.USER, "보관 사실", "보관 본문", "core", MemoryRetrieval.ARCHIVE));
        Memory always = pending(memories.create(ADMIN, MemoryScope.USER, "늘 싣는 사실", "늘 본문", true));
        Memory superseded = pending(memories.create(ADMIN, MemoryScope.USER, "옛 사실", "옛 본문", false));
        MemorySupersedeRows.supersede(jdbc, superseded.id(), shortFact.id());
        Memory group = pending(memories.create(ADMIN, MemoryScope.GROUP, "그룹 사실", "그룹 본문", false));
        Memory document = pending(memories.createDocument(ADMIN, "core", "doc-backfill", "문서", "문서 본문"));
        Memory proposal = pending(memories.proposeUser(ADMIN, "제안 사실", "제안 본문", null));
        int before = backfill.backfill();

        assertThat(before).isEqualTo(9);
        assertThat(reread(shortFact).summary()).isEqualTo("짧은 본문");
        assertThat(reread(atLimit).summary()).isEqualTo("나".repeat(200));
        for (Memory other : List.of(longFact, archived, always, superseded, group, document, proposal)) {
            Memory read = reread(other);
            assertThat(read.summaryPending()).as(other.title()).isFalse();
            assertThat(read.summary()).as(other.title()).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT summary FROM memory WHERE id = ?", String.class, shortFact.id()))
            .startsWith("v1.").doesNotContain("짧은 본문");
        assertThat(reread(shortFact).revision()).isEqualTo(1);
        assertThat(backfill.backfill()).isZero();
    }


    @Test
    @DisplayName("이관 뒤 대상 기억은 모두 요약 구역에 든다")
    void placesBackfilledFactsInSummarySection() {
        Memory first = pending(memories.create(ADMIN, MemoryScope.USER, "딸 이름", "홍지수", false));
        Memory second = pending(memories.create(ADMIN, MemoryScope.USER, "음식 선호", "매운 음식을 못 먹는다", false));
        assertThat(assembler.assembleForOwner(ADMIN).instructions()).doesNotContain("홍지수");

        backfill.backfill();

        assertThat(assembler.assembleForOwner(ADMIN).instructions())
            .contains("- [" + first.id() + "] 딸 이름: 홍지수", "- [" + second.id() + "] 음식 선호: 매운 음식을 못 먹는다");
    }


    @Test
    @DisplayName("풀지 못한 줄은 건너뛰고 이관 전으로 남으며 다른 줄은 적는다")
    void skipsUnreadableRows() {
        Memory broken = pending(memories.create(ADMIN, MemoryScope.USER, "깨진 사실", "깨진 본문", false));
        Memory fine = pending(memories.create(ADMIN, MemoryScope.USER, "멀쩡한 사실", "멀쩡한 본문", false));
        jdbc.update("UPDATE memory SET owner_user_id = 2 WHERE id = ?", broken.id());

        assertThat(backfill.backfill()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT summary FROM memory WHERE id = ?", String.class, broken.id())).isNull();
        assertThat(reread(fine).summary()).isEqualTo("멀쩡한 본문");
    }
}
