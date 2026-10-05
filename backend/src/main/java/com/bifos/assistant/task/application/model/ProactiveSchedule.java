package com.bifos.assistant.task.application.model;

import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckInvalidReason;
import com.bifos.assistant.proactive.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import java.time.Instant;
import java.util.List;

/** 에이전트별 매일 깨우기 설정과 화면에 보일 상태다. */
public record ProactiveSchedule(
        boolean enabled,
        String time,
        String timeZone,
        Instant nextRunAt,
        LastCheck lastCheck,
        boolean schedulingAvailable,
        List<CheckBlocker> blockers) {

    public ProactiveSchedule {
        blockers = List.copyOf(blockers);
    }

    /** 매일 깨우기가 마지막으로 연 살펴보기다. */
    public record LastCheck(
            CheckStatus status,
            CheckOutcome outcome,
            CheckInvalidReason invalidReason,
            CheckSkippedReason skippedReason,
            Instant startedAt,
            Instant finishedAt) {}
}
