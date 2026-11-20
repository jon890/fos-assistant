package com.bifos.assistant.workspace.infra;

/** 링크 경쟁 재현의 시간이다. 기본 5초다. */
public final class LinkRaceSettings {

    private LinkRaceSettings() {
    }


    public static long nanos() {
        return Long.getLong("linkRace.seconds", 5L) * 1_000_000_000L;
    }
}
