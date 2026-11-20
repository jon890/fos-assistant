package com.bifos.assistant.admin.usage.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 요약의 「품질과 실패」 카드가 보이는 그룹 전체의 최근 실패다.
 *
 * @param since 세기 시작한 시각. 서울 날짜로 오늘을 포함한 7일의 첫 0시다
 * @param total 그 뒤로 실패한 루트 실행 수
 * @param recent 가장 최근 실패 몇 건
 */
public record OverviewFailures(Instant since, long total, List<Line> recent) {

    public OverviewFailures {
        recent = List.copyOf(recent);
    }


    /**
     * 실패 한 줄이다. 남의 실행이면 대화와 도구 원본은 넣지 않고 이 칸만 낸다.
     *
     * @param userName 실행한 사용자 이름. 찾지 못하면 null
     * @param agentName 에이전트 이름. 에이전트가 지워졌으면 null
     * @param mine 요청한 관리자 자신의 실행이면 참. 화면은 이 줄만 실행 상세로 잇는다
     */
    public record Line(Long id, String userName, String agentName, Instant startedAt, String errorCode, boolean mine) {
    }
}
