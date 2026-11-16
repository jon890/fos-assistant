package com.bifos.assistant.connector.action.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 승인한 실행이 사람이 해야 하는 화면에서 멈춰 같은 승인으로 이어 갈 표식이다(docs/features/browser-cowork/spec.md 의 REQ8, REQ9).
 * 판정 줄의 {@code waiting_since}, {@code resume_step}, {@code resumed_at} 칸으로 저장하고, 멈춘 적 없는 줄은 칸이 모두 비어 있다.
 *
 * <p>이어 갈 탭은 같은 줄의 넘기기 탭({@code handoff_tab})이다. 뜻은 {@code backend/docs/data-schema.md} 의 「connector_action」 이
 * 갖는다.
 */
@Embeddable
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionWaiting {

    /** 처음 멈춘 시각이다. 다시 멈춰도 바꾸지 않는다. 표식의 수명은 이 시각부터 센다. */
    @Column(name = "waiting_since")
    private Instant since;

    /** 플러그인이 낸 이어 갈 단계다. Control Plane 은 뜻을 모르고 그대로 돌려준다. */
    @Column(name = "resume_step", length = 32)
    private String step;

    /** 마지막으로 이어 간 시각이다. 이어 간 실행이 오래 남았는지 이 시각으로 본다. */
    @Column(name = "resumed_at")
    private Instant resumedAt;


    private ActionWaiting(Instant since, String step, Instant resumedAt) {
        this.since = since;
        this.step = step;
        this.resumedAt = resumedAt;
    }


    /** 멈췄다. 처음 멈춘 시각은 앞의 표식에서 그대로 잇는다. */
    static ActionWaiting stopped(ActionWaiting previous, String step, Instant now) {
        boolean first = previous == null || previous.since == null;
        return new ActionWaiting(first ? now : previous.since, Objects.requireNonNull(step), first ? null : previous.resumedAt);
    }


    /** 이어 간다. */
    ActionWaiting resumed(Instant now) {
        return new ActionWaiting(since, step, now);
    }
}
