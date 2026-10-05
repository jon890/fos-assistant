package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청자의 개인 Memory 제안을 나를 기다리는 카드의 후보로 낸다.
 *
 * <p>모델이 낸 제안이라 {@code NOW} 가 되지 못한다. 기억 메뉴의 제안 건수에 이미 센다.
 */
@Component
@RequiredArgsConstructor
public class MemoryProposalCandidates implements AttentionCandidates {

    private final MemoryService memories;

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.NEEDS_ME);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        return memories.proposalsOf(user).stream()
                .map(MemoryProposalCandidates::candidate)
                .toList();
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
