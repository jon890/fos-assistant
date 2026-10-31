package com.bifos.assistant.memory.maintenance.presentation;

import com.bifos.assistant.memory.maintenance.application.dto.MaintenanceCandidate;
import com.bifos.assistant.memory.maintenance.application.dto.MaintenanceReview;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MaintenanceDtos {

    /** 정비 후보 한 줄이다. 제목은 지금 항목에서 읽고 본문은 싣지 않는다. */
    public record MaintenanceCandidateView(
        Long id,
        Long memoryId,
        String scope,
        String title,
        String action,
        String summary,
        boolean archivable,
        Instant proposedAt) {
        static MaintenanceCandidateView from(MaintenanceCandidate candidate) {
            return new MaintenanceCandidateView(
                candidate.id(),
                candidate.memoryId(),
                candidate.scope().name(),
                candidate.title(),
                candidate.action(),
                candidate.summary(),
                candidate.archivable(),
                candidate.proposedAt());
        }
    }

    public record MaintenanceReviewRequest(@NotNull MaintenanceReview review) {
    }
}
