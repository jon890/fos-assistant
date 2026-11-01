package com.bifos.assistant.proactive.loop.presentation;

import com.bifos.assistant.proactive.loop.application.dto.LoopSettingView;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 에이전트 화면의 매일 루프 설정이 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProactiveLoopDtos {

    /**
     * 에이전트 하나의 매일 루프 설정을 바꾼다.
     *
     * @param snoozedUntil 비우거나 지금부터 30일 안의 시각. 지난 시각은 비운 것과 같다
     */
    public record LoopSettingBody(@NotNull Boolean enabled, Instant snoozedUntil) {
    }

    /**
     * 에이전트 하나의 매일 루프 설정이다.
     *
     * @param available 설치가 매일 루프를 연다. 거짓이면 켤 수 없다
     * @param snoozedUntil 쉬는 끝 시각. 지금보다 뒤일 때만 싣는다
     */
    public record LoopSettingResponse(boolean available, boolean enabled, Instant snoozedUntil) {

        static LoopSettingResponse from(LoopSettingView view) {
            return new LoopSettingResponse(view.available(), view.enabled(), view.snoozedUntil());
        }
    }
}
