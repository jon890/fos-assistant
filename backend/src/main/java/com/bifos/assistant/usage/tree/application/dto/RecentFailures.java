package com.bifos.assistant.usage.tree.application.dto;

import java.util.List;

/**
 * 한 구간의 실패한 루트 실행 수와 가장 최근 몇 건이다.
 *
 * @param total 구간 안의 실패한 루트 실행 수
 * @param recent 시작 시각, 번호 내림차순의 최근 실패
 */
public record RecentFailures(long total, List<FailedRoot> recent) {

    public RecentFailures {
        recent = List.copyOf(recent);
    }
}
