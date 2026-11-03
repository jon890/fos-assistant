package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.dto.MemorySource;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MemoryDtos {
    public record CreateMemoryRequest(
        MemoryScope scope,
        @NotBlank @Size(max = 200) String title,
        @NotBlank String content,
        Boolean alwaysInject) {
    }

    /** {@code archivable} 은 자동 보관 허락 표시다. 없으면 지금 값을 그대로 둔다. */
    public record UpdateMemoryRequest(
        @NotBlank String content, @NotNull Boolean alwaysInject, Boolean archivable) {
    }

    /**
     * 화면에 보내는 Memory 한 줄이다.
     *
     * <p>{@code omittedFromContext} 는 지금 조립하면 자리가 없어 실리지 않을 항목이라는 뜻이다.
     * 본문이 상한을 넘거나 앞선 항목들이 자리를 다 쓴 경우가 여기 해당한다. 목록을 조회할 때만
     * 판정하고, 한 항목만 돌려주는 응답은 언제나 거짓이다.
     *
     * <p>{@code sourceAgentName} 과 {@code sourceAgentDeleted} 는 에이전트가 남긴 기억의 출처다. 목록을 조회할 때만
     * 채우고 뜻은 {@link MemorySource} 가 갖는다. 에이전트가 남기지 않았거나 한 항목만 돌려주는 응답은 null 과 거짓이다.
     *
     * <p>{@code unreadable} 이 참이면 그 줄을 풀지 못했다. 제목은 「읽지 못함」, 본문은 빈 글이고 화면은 고치기를 막는다
     * (ADR-20261103 / memory-encryption).
     */
    public record MemoryView(
        Long id,
        String scope,
        Long ownerUserId,
        Long groupId,
        String title,
        String content,
        boolean alwaysInject,
        String status,
        Long proposedByExecutionId,
        Long acceptedByUserId,
        Instant acceptedAt,
        Instant createdAt,
        Instant updatedAt,
        boolean omittedFromContext,
        String sourceAgentName,
        boolean sourceAgentDeleted,
        boolean archivable,
        boolean unreadable) {
        static MemoryView from(Memory memory) {
            return from(memory, false, null);
        }


        static MemoryView from(Memory memory, boolean omittedFromContext, MemorySource source) {
            return new MemoryView(
                memory.id(),
                memory.scope().name(),
                memory.ownerUserId(),
                memory.groupId(),
                memory.title(),
                memory.readable() ? memory.content() : "",
                memory.alwaysInject(),
                memory.status().name(),
                memory.proposedByExecutionId(),
                memory.acceptedByUserId(),
                memory.acceptedAt(),
                memory.createdAt(),
                memory.updatedAt(),
                omittedFromContext,
                source == null ? null : source.agentName(),
                source != null && source.agentDeleted(),
                memory.archivable(),
                !memory.readable());
        }
    }

    /** 문서를 만든다. 꺼내는 방식은 받지 않는다. 늘 SEARCH 다(ADR-057). */
    public record CreateDocumentRequest(
        @NotBlank String collection,
        @NotBlank String documentKey,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 12000) String content) {
    }

    /** 문서를 고친다. {@code expectedRevision} 은 화면이 읽은 판 번호다. */
    public record UpdateDocumentRequest(
        @NotBlank @Size(max = 12000) String content,
        @NotNull Integer expectedRevision) {
    }

    /** 문서 목록의 한 줄이다. 본문을 싣지 않는다. */
    public record DocumentSummaryView(
        Long id,
        String collection,
        String documentKey,
        String title,
        int revision,
        Instant updatedAt) {
        static DocumentSummaryView from(Memory memory) {
            return new DocumentSummaryView(
                memory.id(),
                memory.collection(),
                memory.documentKey(),
                memory.title(),
                memory.revision(),
                memory.updatedAt());
        }
    }

    /** 문서 하나다. 본문은 평문이다. */
    public record DocumentView(
        Long id,
        String collection,
        String documentKey,
        String title,
        int revision,
        Instant updatedAt,
        String content) {
        static DocumentView from(Memory memory, String content) {
            return new DocumentView(
                memory.id(),
                memory.collection(),
                memory.documentKey(),
                memory.title(),
                memory.revision(),
                memory.updatedAt(),
                content);
        }
    }

    public record CollectionView(String key, String displayName) {
        static CollectionView from(MemoryCollection collection) {
            return new CollectionView(collection.key(), collection.displayName());
        }
    }

}
