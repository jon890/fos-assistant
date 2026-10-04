package com.bifos.assistant.proactive.application.model;

import java.util.List;

/**
 * 살펴보기를 막는 까닭 하나다.
 *
 * @param toolsets {@link CheckBlockerCode#TOOLSETS_NOT_ALLOWED} 일 때 허용 목록 밖의 켜진 toolset 이름을 정렬해 싣는다.
 *     다른 까닭이면 비어 있다
 */
public record CheckBlocker(CheckBlockerCode code, List<String> toolsets) {

    public CheckBlocker {
        toolsets = List.copyOf(toolsets);
    }

    public static CheckBlocker of(CheckBlockerCode code) {
        return new CheckBlocker(code, List.of());
    }
}
