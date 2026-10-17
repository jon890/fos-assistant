package com.bifos.assistant.skill.presentation;

import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillUsageSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 스킬 경로가 주고받는 모양이다. 사용자 경로와 계약이 따로 바뀔 수 있어 겹치는 record 는 {@code SkillDtos} 에서 복사해 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SkillAdminDtos {

    /**
     * 스킬 목록 화면이 받는 것이다.
     *
     * @param editable 지금 요청자가 스킬을 올리고 지울 수 있는가
     * @param skillsToolsetEnabled 그 에이전트의 API 실행에 {@code skills} toolset 이 켜져 있는가
     * @param uploadLimit 그 에이전트에 올릴 수 있는 스킬 수의 한도
     */
    public record SkillListView(
        List<SkillItemView> skills, boolean editable, boolean skillsToolsetEnabled, int uploadLimit) {
        static SkillListView from(SkillList list) {
            return new SkillListView(
                list.skills().stream().map(SkillItemView::from).toList(),
                list.editable(),
                list.skillsToolsetEnabled(),
                list.uploadLimit());
        }
    }

    /** 스킬을 전역으로 켜거나 끄는 요청이다. */
    public record ToggleSkillRequest(@NotNull Boolean enabled) {
    }

    /**
     * 스킬 목록의 한 줄이다.
     *
     * @param source {@code UPLOADED} 나 {@code HERMES}
     * @param usage 호출 합계. 관리하는 사람에게만 주고, 없으면 칸을 빼고 보낸다
     */
    public record SkillItemView(
        String name,
        String description,
        String source,
        boolean enabled,
        @JsonInclude(JsonInclude.Include.NON_NULL) SkillUsageView usage) {
        static SkillItemView from(SkillListItem item) {
            return new SkillItemView(
                item.name(),
                item.description(),
                item.source().name(),
                item.enabled(),
                SkillUsageView.from(item.usage()));
        }
    }

    /** 스킬 하나의 호출 수와 마지막 호출 시각이다. */
    public record SkillUsageView(long count, Instant lastInvokedAt) {
        static SkillUsageView from(SkillUsageSummary usage) {
            return usage == null ? null : new SkillUsageView(usage.count(), usage.lastInvokedAt());
        }
    }
}
