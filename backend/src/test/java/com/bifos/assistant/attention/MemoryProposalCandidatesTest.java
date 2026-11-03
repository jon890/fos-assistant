package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.attention.application.MemoryProposalCandidates;
import com.bifos.assistant.attention.application.dto.AttentionCandidate;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 기억 제안이 지금 화면의 나를 기다리는 카드 후보로 나오는지 확인한다. */
@BackendIntegrationTest
class MemoryProposalCandidatesTest {

    private static final CurrentUser OWNER = new CurrentUser(1L, "owner@example.com", "주인", 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = new CurrentUser(2L, "member@example.com", "가족", 10L, UserRole.MEMBER);

    @Autowired
    MemoryProposalCandidates candidates;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;


    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }


    @Test
    @DisplayName("요청자의 기억 제안은 NEEDS_ME 후보이고 다른 사용자의 것은 나오지 않는다")
    void countsMemoryProposalsAsNeedsMe() {
        Memory proposal = memories.proposeUser(OWNER, "제안", "내용", 1L);

        assertThat(candidates.read(OWNER, Instant.now()))
            .extracting(AttentionCandidate::card, AttentionCandidate::trigger, AttentionCandidate::itemKey, AttentionCandidate::title)
            .containsExactly(tuple(CardKey.NEEDS_ME, AttentionTrigger.MEMORY_PROPOSED, "memory:" + proposal.id(), "제안"));
        assertThat(candidates.read(MEMBER, Instant.now())).isEmpty();
    }
}
