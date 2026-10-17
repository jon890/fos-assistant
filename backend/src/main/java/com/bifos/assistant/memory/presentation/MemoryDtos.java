package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.dto.MemorySource;
import com.bifos.assistant.memory.application.dto.SupersedeProposal;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCollection;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
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
        @NotBlank String title,
        @NotBlank String content,
        Boolean alwaysInject) {
    }

    public record UpdateMemoryRequest(
        @NotBlank String content, @NotNull Boolean alwaysInject) {
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
     * <p>{@code supersededBy} 는 효력 있는 대체 연결이 이 항목을 가리킬 때 그 연결과 대신한 항목이다(ADR-20261015 / memory-link). 목록을
     * 조회할 때만 채운다. 대체된 항목은 에이전트에게 실리지 않지만 이 목록에는 남아 되돌릴 수 있다.
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
        boolean sensitive,
        boolean omittedFromContext,
        String sourceAgentName,
        boolean sourceAgentDeleted,
        SupersededByView supersededBy) {
        static MemoryView from(Memory memory) {
            return from(memory, false, null, null);
        }


        static MemoryView from(
            Memory memory, boolean omittedFromContext, MemorySource source, SupersededByView supersededBy) {
            boolean sensitive = memory.sensitivity() == MemorySensitivity.SENSITIVE;
            return new MemoryView(
                memory.id(),
                memory.scope().name(),
                memory.ownerUserId(),
                memory.groupId(),
                memory.title(),
                sensitive || memory.sealed() ? "" : memory.content(),
                memory.alwaysInject(),
                memory.status().name(),
                memory.proposedByExecutionId(),
                memory.acceptedByUserId(),
                memory.acceptedAt(),
                memory.createdAt(),
                memory.updatedAt(),
                sensitive,
                omittedFromContext,
                source == null ? null : source.agentName(),
                source != null && source.agentDeleted(),
                supersededBy);
        }
    }

    /** 이 항목을 대신한 효력 있는 연결과 그 새 항목의 제목이다. 되돌리기는 {@code linkId} 로 한다. */
    public record SupersededByView(Long linkId, Long memoryId, String title) {
    }

    /** 대체 제안 카드의 한 항목이다. 본문은 싣지 않는다. */
    public record MemoryRefView(Long id, String title) {
    }

    /**
     * 사람이 받아들이거나 거절할 대체 제안 하나다. 새 항목이 옛 항목을 대신한다.
     *
     * @param origin {@code AGENT}, {@code HUMAN}, {@code AUTO}
     */
    public record SupersedeProposalView(
        Long id, MemoryRefView newMemory, MemoryRefView oldMemory, String origin, Instant createdAt) {
        static SupersedeProposalView from(SupersedeProposal proposal) {
            return new SupersedeProposalView(
                proposal.link().id(),
                new MemoryRefView(proposal.newMemory().id(), proposal.newMemory().title()),
                new MemoryRefView(proposal.oldMemory().id(), proposal.oldMemory().title()),
                proposal.link().origin().name(),
                proposal.link().createdAt());
        }
    }

    /** 받아들이기, 거절, 되돌리기의 결과다. */
    public record MemoryLinkView(Long id, String type, String status) {
        static MemoryLinkView from(MemoryLink link) {
            return new MemoryLinkView(link.id(), link.type().name(), link.status().name());
        }
    }

    /** 문서를 만든다. 꺼내는 방식은 받지 않는다. 늘 SEARCH 다(ADR-057). */
    public record CreateDocumentRequest(
        @NotBlank String collection,
        @NotBlank String documentKey,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 12000) String content,
        @NotNull Boolean sensitive) {
    }

    /** 문서를 고친다. {@code expectedRevision} 은 화면이 읽은 판 번호다. */
    public record UpdateDocumentRequest(
        @NotBlank @Size(max = 12000) String content,
        @NotNull Boolean sensitive,
        @NotNull Integer expectedRevision) {
    }

    /** 문서 목록의 한 줄이다. 본문을 싣지 않는다. */
    public record DocumentSummaryView(
        Long id,
        String collection,
        String documentKey,
        String title,
        boolean sensitive,
        int revision,
        Instant updatedAt) {
        static DocumentSummaryView from(Memory memory) {
            return new DocumentSummaryView(
                memory.id(),
                memory.collection(),
                memory.documentKey(),
                memory.title(),
                memory.sensitivity() == MemorySensitivity.SENSITIVE,
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
        boolean sensitive,
        int revision,
        Instant updatedAt,
        String content) {
        static DocumentView from(Memory memory, String content) {
            return new DocumentView(
                memory.id(),
                memory.collection(),
                memory.documentKey(),
                memory.title(),
                memory.sensitivity() == MemorySensitivity.SENSITIVE,
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
