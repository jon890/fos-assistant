package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public final class MemoryDtos {
    private MemoryDtos() {}

    public record CreateMemoryRequest(
            MemoryScope scope,
            @NotBlank String title,
            @NotBlank String content,
            Boolean alwaysInject) {}

    public record UpdateMemoryRequest(@NotBlank String content, @NotNull Boolean alwaysInject) {}

    public record MemoryView(Long id, String scope, Long ownerUserId, Long familyId, String title,
            String content, boolean alwaysInject, String status, Long proposedByExecutionId,
            Long acceptedByUserId, Instant acceptedAt, Instant createdAt, Instant updatedAt) {
        static MemoryView from(Memory memory) {
            return new MemoryView(memory.id(), memory.scope().name(), memory.ownerUserId(),
                    memory.familyId(), memory.title(), memory.content(), memory.alwaysInject(),
                    memory.status().name(), memory.proposedByExecutionId(), memory.acceptedByUserId(),
                    memory.acceptedAt(), memory.createdAt(), memory.updatedAt());
        }
    }
}
