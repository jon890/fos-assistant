package com.bifos.assistant.memory.agent.presentation;

import com.bifos.assistant.memory.agent.application.model.AgentMemoryGrantInput;
import com.bifos.assistant.memory.agent.application.model.AgentMemorySetting;
import com.bifos.assistant.memory.agent.application.model.AgentMemorySettingChange;
import com.bifos.assistant.memory.agent.application.model.AgentMemorySettingCollection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 에이전트 Memory 접근 설정의 HTTP 요청과 응답이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentMemoryDtos {
    /** 에이전트에 줄 collection 하나다. */
    public record AgentMemoryGrantBody(
        @NotBlank @Size(max = 64) String collection, boolean allowSensitive) {
        AgentMemoryGrantInput toInput() {
            return new AgentMemoryGrantInput(collection, allowSensitive);
        }
    }

    /** 에이전트가 받을 collection 전체다. 빈 목록이면 모두 뗀다. */
    public record ReplaceAgentMemoryRequest(
        @NotNull @Size(max = 64) List<@Valid @NotNull AgentMemoryGrantBody> collections) {
    }

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
