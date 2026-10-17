package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.port.AttentionCandidates;
import com.bifos.assistant.attention.application.dto.AttentionCandidate;
import com.bifos.assistant.attention.application.dto.AttentionConfidence;
import com.bifos.assistant.attention.application.dto.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.SupersedeProposal;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청자의 개인 Memory 제안과 요청자가 받아들일 수 있는 대체 제안을 나를 기다리는 카드의 후보로 낸다.
 *
 * <p>모델이 낸 제안이라 {@code NOW} 가 되지 못한다. 기억 메뉴의 제안 건수에 이미 센다. 대체 제안은 기억 화면의 「검토할 기억」 에서
 * 정한다(ADR-20261015 / memory-link).
 */
@Component
@RequiredArgsConstructor
public class MemoryProposalCandidates implements AttentionCandidates {

    private final MemoryService memories;
    private final MemoryLinkService links;


    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.NEEDS_ME);
    }


    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        return Stream.concat(
            memories.proposalsOf(user).stream().map(MemoryProposalCandidates::candidate),
            links.pendingSupersedesFor(user).stream().map(MemoryProposalCandidates::supersede))
            .toList();
    }


    /** 새 기억의 제목으로 보인다. 옛 기억의 제목은 기억 화면의 카드가 함께 보인다. */
    private static AttentionCandidate supersede(SupersedeProposal proposal) {
        String itemKey = "memory-link:" + proposal.link().id();
        return new AttentionCandidate(
            CardKey.NEEDS_ME,
            itemKey,
            AttentionCandidates.stateKey(
                AttentionTrigger.MEMORY_SUPERSEDE_PROPOSED, proposal.link().updatedAt().toString()),
            AttentionTrigger.MEMORY_SUPERSEDE_PROPOSED,
            false,
            false,
            List.of(),
            AttentionConfidence.MODEL_INFERRED,
            proposal.newMemory().title(),
            null,
            null,
            proposal.link().updatedAt(),
            List.of(new AttentionSourceRef("MEMORY_LINK", itemKey, proposal.link().updatedAt())),
            null,
            null,
            null);
    }


    private static AttentionCandidate candidate(Memory memory) {
        String itemKey = "memory:" + memory.id();
        return new AttentionCandidate(
            CardKey.NEEDS_ME,
            itemKey,
            AttentionCandidates.stateKey(AttentionTrigger.MEMORY_PROPOSED, String.valueOf(memory.revision())),
            AttentionTrigger.MEMORY_PROPOSED,
            false,
            false,
            List.of(),
            AttentionConfidence.MODEL_INFERRED,
            memory.title(),
            null,
            null,
            memory.updatedAt(),
            List.of(new AttentionSourceRef("MEMORY_PROPOSAL", itemKey, memory.updatedAt())),
            null,
            null,
            null);
    }
}
