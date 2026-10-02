package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.model.ServiceTokenGrant;
import com.bifos.assistant.memory.application.model.ServiceTokenSnapshot;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MemoryDtos {
    public record CreateMemoryRequest(
            MemoryScope scope,
            @NotBlank String title,
            @NotBlank String content,
            Boolean alwaysInject) {}

    public record UpdateMemoryRequest(
            @NotBlank String content, @NotNull Boolean alwaysInject) {}

    /**
     * 화면에 보내는 Memory 한 줄이다.
     *
     * <p>{@code omittedFromContext} 는 지금 조립하면 자리가 없어 실리지 않을 항목이라는 뜻이다.
     * 본문이 상한을 넘거나 앞선 항목들이 자리를 다 쓴 경우가 여기 해당한다. 목록을 조회할 때만
     * 판정하고, 한 항목만 돌려주는 응답은 언제나 거짓이다.
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
            boolean omittedFromContext) {
        static MemoryView from(Memory memory) {
            return from(memory, false);
        }

        static MemoryView from(Memory memory, boolean omittedFromContext) {
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
                    omittedFromContext);
        }
    }

    /** 문서를 만든다. 꺼내는 방식은 받지 않는다. 늘 SEARCH 다(ADR-057). */
    public record CreateDocumentRequest(
            @NotBlank String collection,
            @NotBlank String documentKey,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 12000) String content,
            @NotNull Boolean sensitive) {}

    /** 문서를 고친다. {@code expectedRevision} 은 화면이 읽은 판 번호다. */
    public record UpdateDocumentRequest(
            @NotBlank @Size(max = 12000) String content,
            @NotNull Boolean sensitive,
            @NotNull Integer expectedRevision) {}

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

    public record ServiceTokenGrantBody(@NotBlank String collection, boolean allowSensitive) {
        ServiceTokenGrant toGrant() {
            return new ServiceTokenGrant(collection, allowSensitive);
        }
    }

    /** 서비스 토큰을 발급한다. 만료는 필수이고 1일에서 365일 사이다(ADR-056). */
    public record IssueServiceTokenRequest(
            @NotBlank @Size(max = 100) String label,
            @NotNull @Min(1) @Max(365) Integer expiresInDays,
            @NotEmpty @Valid List<ServiceTokenGrantBody> collections) {}

    /** 서비스 토큰 한 줄이다. 원문과 해시는 담지 않는다. */
    public record ServiceTokenView(
            Long id,
            String label,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            List<ServiceTokenGrantBody> collections) {
        static ServiceTokenView from(ServiceTokenSnapshot snapshot) {
            var token = snapshot.token();
            return new ServiceTokenView(
                    token.id(),
                    token.label(),
                    token.createdAt(),
                    token.expiresAt(),
                    token.lastUsedAt(),
                    token.revokedAt(),
                    snapshot.grants().stream()
                            .map(grant -> new ServiceTokenGrantBody(grant.collection(), grant.allowSensitive()))
                            .toList());
        }
    }

    /** 발급 응답이다. 원문 {@code token} 은 여기서만 나온다. */
    public record IssuedServiceTokenView(ServiceTokenView info, String token) {}

    /** 서비스가 읽는 문서다. 본문은 평문이고 {@code revision} 은 지금 값의 판 번호다(ADR-056). */
    public record ServiceDocumentView(
            String collection, String documentKey, String title, String content, int revision, Instant updatedAt) {}

    /** 묶음의 항목 하나다. 틀린 칸은 요청 전체가 아니라 그 항목의 REJECTED 로 답하므로 검증 주석을 달지 않는다(ADR-058). */
    public record ImportItemBody(
            String sourceRef,
            LocalDate sourceDate,
            String collection,
            String entryType,
            String documentKey,
            String title,
            String content,
            boolean sensitive,
            String retrieval) {}

    public record ImportRequest(
            @NotNull Integer schemaVersion,
            @NotEmpty @Size(max = 100) List<ImportItemBody> items) {}

    /** 항목의 결과다. {@code sourceRef} 를 싣지 않는다. 화면은 {@code index} 로 자기가 올린 항목과 맞춘다. */
    public record ImportOutcomeView(int index, String status, String reason, Long memoryId) {}

    public record ImportResponse(
            int newCount, int duplicateCount, int conflictCount, int rejectedCount, List<ImportOutcomeView> items) {}
}
