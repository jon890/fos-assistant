package com.bifos.assistant.admin.memory.presentation;

import com.bifos.assistant.memory.application.MemoryContextSettings;
import com.bifos.assistant.memory.application.dto.MemoryContextBudget;
import com.bifos.assistant.memory.application.dto.AgentMemoryGrantInput;
import com.bifos.assistant.memory.application.dto.AgentMemorySetting;
import com.bifos.assistant.memory.application.dto.AgentMemorySettingChange;
import com.bifos.assistant.memory.application.dto.AgentMemorySettingCollection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 에이전트 Memory 설정 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MemoryAdminDtos {

    /** 에이전트에 줄 collection 하나다. */
    public record AgentMemoryGrantBody(@NotBlank @Size(max = 64) String collection) {
        AgentMemoryGrantInput toInput() {
            return new AgentMemoryGrantInput(collection);
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
        long entryCount) {
        static AgentMemoryCollectionView from(AgentMemorySettingCollection collection) {
            return new AgentMemoryCollectionView(
                collection.key(),
                collection.displayName(),
                collection.listed(),
                collection.granted(),
                collection.entryCount());
        }
    }

    /** 받는 collection 이 바뀐 기록 한 줄이다. */
    public record AgentMemoryChangeView(
        String collection, String changeType, String changedByName, Instant changedAt) {
        static AgentMemoryChangeView from(AgentMemorySettingChange change) {
            return new AgentMemoryChangeView(
                change.collection(),
                change.changeType(),
                change.changedByName(),
                change.changedAt());
        }
    }

    /** 관리자가 바꾸는 기억 조립 예산이다. 범위는 서버가 저장할 때 검사한다. */
    public record ReplaceMemoryContextRequest(@NotNull Long summaryMaxChars, @NotNull Long maxChars) {
    }

    /**
     * 그룹의 지금 기억 조립 예산과 기본값, 허용 범위다.
     *
     * @param summaryCeiling 지금 전체 상한에서 요약 구역 예산이 가질 수 있는 최댓값. 전체 상한에서 색인 몫을 뺀 자리다
     */
    public record MemoryContextSettingView(
        long summaryMaxChars,
        long maxChars,
        long defaultSummaryMaxChars,
        long defaultMaxChars,
        long summaryMin,
        long summaryCeiling,
        long maxCharsMin,
        long maxCharsMax) {

        public static MemoryContextSettingView of(MemoryContextBudget budget, MemoryContextSettings settings) {
            return new MemoryContextSettingView(
                budget.summaryMaxChars(),
                budget.maxChars(),
                MemoryContextBudget.DEFAULT_SUMMARY_MAX_CHARS,
                MemoryContextBudget.DEFAULT_MAX_CHARS,
                MemoryContextSettings.SUMMARY_MIN,
                settings.summaryCeiling(budget.maxChars()),
                MemoryContextSettings.TOTAL_MIN,
                MemoryContextSettings.TOTAL_MAX);
        }
    }
}
