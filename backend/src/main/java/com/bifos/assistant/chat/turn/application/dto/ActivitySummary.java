package com.bifos.assistant.chat.turn.application.dto;

import java.util.List;

/**
 * 답을 만들며 호출한 도구와 하위 에이전트 수와 걸린 시간이다.
 *
 * @param uses 그 답이 쓴 도구와 도우미를 처음 쓴 차례로, 같은 것은 한 번만 담는다
 */
public record ActivitySummary(int toolCount, int subagentCount, Long durationMs, List<ActivityUse> uses) {

    public ActivitySummary {
        uses = uses == null ? List.of() : List.copyOf(uses);
    }


    /** 쓴 것의 목록을 모르는 요약이다. */
    public ActivitySummary(int toolCount, int subagentCount, Long durationMs) {
        this(toolCount, subagentCount, durationMs, List.of());
    }
}
