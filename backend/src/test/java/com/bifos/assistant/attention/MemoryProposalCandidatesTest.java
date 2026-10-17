package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.attention.application.MemoryProposalCandidates;
import com.bifos.assistant.attention.application.dto.AttentionCandidate;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 기억 제안과 대체 제안이 지금 화면의 나를 기다리는 카드 후보로 나오는지 확인한다. */
@BackendIntegrationTest
class MemoryProposalCandidatesTest {

    private static final CurrentUser OWNER = new CurrentUser(1L, "owner@example.com", "주인", 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = new CurrentUser(2L, "member@example.com", "가족", 10L, UserRole.MEMBER);

    @Autowired
    MemoryProposalCandidates candidates;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryLinkRepository links;


    @BeforeEach
    void setUp() {
        links.deleteAll();
        repository.deleteAll();
    }


    @Test
    @DisplayName("받아들일 수 있는 대체 제안은 기억 제안과 함께 NEEDS_ME 후보이고 받아들이면 빠진다")
    void countsSupersedeProposalsAsNeedsMe() {
        Memory proposal = memories.proposeUser(OWNER, "제안", "내용", 1L);
        Memory old = memories.create(OWNER, MemoryScope.GROUP, "옛 규칙", "1", false);
        Memory fresh = memories.create(OWNER, MemoryScope.GROUP, "새 규칙", "2", false);
        MemoryLink link = linkService.proposeSupersede(MEMBER, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);

        assertThat(candidates.read(OWNER, Instant.now()))
            .extracting(AttentionCandidate::card, AttentionCandidate::trigger, AttentionCandidate::itemKey, AttentionCandidate::title)
            .containsExactly(
                tuple(CardKey.NEEDS_ME, AttentionTrigger.MEMORY_PROPOSED, "memory:" + proposal.id(), "제안"),
                tuple(CardKey.NEEDS_ME, AttentionTrigger.MEMORY_SUPERSEDE_PROPOSED, "memory-link:" + link.id(), "새 규칙"));
        assertThat(candidates.read(MEMBER, Instant.now()))
            .as("그룹 항목의 대체는 ADMIN 만 정한다")
            .isEmpty();

        linkService.accept(OWNER, link.id());

        assertThat(candidates.read(OWNER, Instant.now()))
            .extracting(AttentionCandidate::trigger)
            .containsExactly(AttentionTrigger.MEMORY_PROPOSED);
    }
}
