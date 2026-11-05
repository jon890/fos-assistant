package com.bifos.assistant.task.application.dto;

import com.bifos.assistant.proactive.check.application.dto.CheckBlocker;
import java.util.List;

/**
 * 사건 감지를 켜지 못하게 막는 까닭 하나다(ADR-20261105 / event-watch). 사건 실행의 준비 판정이 낸 까닭은 이름을 그대로 옮기고, 연결 쪽
 * 까닭은 아래 상수다. 연결 쪽 뜻은 {@code proactive} 가 모르므로 {@code CheckBlockerCode} 에 두지 않는다.
 *
 * @param code 까닭 이름
 * @param toolsets 걸린 toolset 이름. 그 까닭이 toolset 일 때만 차 있다
 */
public record EventWatchBlocker(String code, List<String> toolsets) {

    /** 조회 도구가 읽기이고 승인이 필요 없는 도구가 아니다. */
    public static final String EVENT_TOOL_NOT_ALLOWED = "EVENT_TOOL_NOT_ALLOWED";
    /** 연결이나 바인딩이 쓸 수 있는 상태가 아니다. */
    public static final String CONNECTION_NOT_READY = "CONNECTION_NOT_READY";
    /** 그 profile 에 사건 실행의 도구 판정을 하는 {@code fos-ctx} 가 없거나 옛 버전이거나 재시작을 기다린다. */
    public static final String TOOL_GUARD_MISSING = "TOOL_GUARD_MISSING";
    /** 같은 연결의 다른 에이전트가 사건 감지를 켰다. */
    public static final String EVENT_WATCH_ELSEWHERE = "EVENT_WATCH_ELSEWHERE";


    public EventWatchBlocker {
        toolsets = List.copyOf(toolsets);
    }


    public static EventWatchBlocker of(CheckBlocker blocker) {
        return new EventWatchBlocker(blocker.code().name(), blocker.toolsets());
    }


    public static EventWatchBlocker of(String code) {
        return new EventWatchBlocker(code, List.of());
    }
}
