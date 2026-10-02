package com.bifos.assistant.chat.application;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;

/** turn 이 Hermes 에 제출한 실행 하나의 참조와 중지 전달 여부다. 칸은 {@link TurnCancellation} 만 고친다. */
@Getter
public final class TurnRunRef {
    final String apiBaseUrl;
    final String profileName;
    final String runId;
    final AtomicBoolean stopSent = new AtomicBoolean();

    TurnRunRef(String apiBaseUrl, String profileName, String runId) {
        this.apiBaseUrl = apiBaseUrl;
        this.profileName = profileName;
        this.runId = runId;
    }
}
