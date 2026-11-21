package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.runtime.domain.RuntimeTarget;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;

/** turn 이 런타임에 제출한 실행 하나의 참조와 중지 전달 여부다. 칸은 {@link TurnCancellation} 만 고친다. */
@Getter
public final class TurnRunRef {
    final RuntimeTarget target;
    final String runId;
    final AtomicBoolean stopSent = new AtomicBoolean();


    TurnRunRef(RuntimeTarget target, String runId) {
        this.target = target;
        this.runId = runId;
    }
}
