package com.bifos.assistant.workspace.infra;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 링크 경쟁 재현의 시간이다. 기본 5초다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LinkRaceSettings {

    public static long nanos() {
        return Long.getLong("linkRace.seconds", 5L) * 1_000_000_000L;
    }
}
