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

    /**
     * 화면에 보내는 Memory 한 줄이다.
     *
     * <p>{@code omittedFromContext} 는 지금 조립하면 자리가 없어 실리지 않을 항목이라는 뜻이다.
     * 본문이 상한을 넘거나 앞선 항목들이 자리를 다 쓴 경우가 여기 해당한다. 목록을 조회할 때만
     * 판정하고, 한 항목만 돌려주는 응답은 언제나 거짓이다.
     */
    public record MemoryView(Long id, String scope, Long ownerUserId, Long familyId, String title,
            String content, boolean alwaysInject, String status, Long proposedByExecutionId,
            Long acceptedByUserId, Instant acceptedAt, Instant createdAt, Instant updatedAt,
            boolean omittedFromContext) {
        static MemoryView from(Memory memory) {
            return from(memory, false);
        }

        static MemoryView from(Memory memory, boolean omittedFromContext) {
            return new MemoryView(memory.id(), memory.scope().name(), memory.ownerUserId(),
                    memory.familyId(), memory.title(), memory.content(), memory.alwaysInject(),
                    memory.status().name(), memory.proposedByExecutionId(), memory.acceptedByUserId(),
                    memory.acceptedAt(), memory.createdAt(), memory.updatedAt(), omittedFromContext);
        }
    }
}
