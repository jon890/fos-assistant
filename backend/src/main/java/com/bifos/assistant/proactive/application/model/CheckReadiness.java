package com.bifos.assistant.proactive.application.model;

import java.util.List;

/** 살펴보기를 시작할 수 있는지다. 걸린 까닭을 모두 담고, 하나도 없어야 시작한다. */
public record CheckReadiness(List<CheckBlocker> blockers) {

    public CheckReadiness {
        blockers = List.copyOf(blockers);
    }

    public boolean available() {
        return blockers.isEmpty();
    }
}
