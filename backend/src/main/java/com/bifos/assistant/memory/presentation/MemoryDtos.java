package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.model.AgentMemoryGrantInput;
import com.bifos.assistant.memory.application.model.AgentMemorySetting;
import com.bifos.assistant.memory.application.model.AgentMemorySettingChange;
import com.bifos.assistant.memory.application.model.AgentMemorySettingCollection;
import com.bifos.assistant.memory.application.model.MemorySource;
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
     *
     * <p>{@code sourceAgentName} 과 {@code sourceAgentDeleted} 는 에이전트가 남긴 기억의 출처다. 목록을 조회할 때만
     * 채우고 뜻은 {@link MemorySource} 가 갖는다. 에이전트가 남기지 않았거나 한 항목만 돌려주는 응답은 null 과 거짓이다.
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
            boolean sourceAgentDeleted) {
        static MemoryView from(Memory memory) {
            return from(memory, false, null);
        }

        static MemoryView from(Memory memory, boolean omittedFromContext, MemorySource source) {
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
                    source != null && source.agentDeleted());
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

    /** 에이전트에 줄 collection 하나다. */
    public record AgentMemoryGrantBody(
            @NotBlank @Size(max = 64) String collection, boolean allowSensitive) {
        AgentMemoryGrantInput toInput() {
            return new AgentMemoryGrantInput(collection, allowSensitive);
        }
    }

    /** 에이전트가 받을 collection 전체다. 빈 목록이면 모두 뗀다. */
    public record ReplaceAgentMemoryRequest(
            @NotNull @Size(max = 64) List<@Valid @NotNull AgentMemoryGrantBody> collections) {}

    /** 관리자가 보는 에이전트의 Memory collection 설정이다. 칸의 뜻은 {@code docs/features/memory.md} 가 갖는다. */
    public record AgentMemorySettingView(
            String countedFor,
            String ownerName,
            List<AgentMemoryCollectionView> collections,
            List<AgentMemoryChangeView> changes) {
        static AgentMemorySettingView from(AgentMemorySetting setting) {
            return new AgentMemorySettingView(
                    setting.countedFor().name(),
                    setting.ownerName(),
                    setting.collections().stream()
                            .map(AgentMemoryCollectionView::from)
                            .toList(),
                    setting.changes().stream().map(AgentMemoryChangeView::from).toList());
        }
    }

    /** 설정 화면의 collection 한 줄이다. 항목의 제목과 번호는 담지 않고 수만 담는다. */
    public record AgentMemoryCollectionView(
            String key,
            String displayName,
            boolean listed,
            boolean granted,
            boolean allowSensitive,
            long entryCount,
            long sensitiveEntryCount) {
        static AgentMemoryCollectionView from(AgentMemorySettingCollection collection) {
            return new AgentMemoryCollectionView(
                    collection.key(),
                    collection.displayName(),
                    collection.listed(),
                    collection.granted(),
                    collection.allowSensitive(),
                    collection.entryCount(),
                    collection.sensitiveEntryCount());
        }
    }

    /** 받는 collection 이 바뀐 기록 한 줄이다. */
    public record AgentMemoryChangeView(
            String collection, String changeType, boolean allowSensitive, String changedByName, Instant changedAt) {
        static AgentMemoryChangeView from(AgentMemorySettingChange change) {
            return new AgentMemoryChangeView(
                    change.collection(),
                    change.changeType(),
                    change.allowSensitive(),
                    change.changedByName(),
                    change.changedAt());
        }
    }
}
