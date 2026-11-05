package com.bifos.assistant.task.application.dto;

import com.bifos.assistant.connector.connection.domain.type.EventWatchResult;
import java.time.Instant;
import java.util.List;

/**
 * 에이전트 상세가 보이는 연결 하나의 사건 감지다(ADR-20261105 / event-watch).
 *
 * @param title 플러그인 제목
 * @param noun 항목 이름. 예: 「메일」
 * @param available 지금 켤 수 있다. 막는 까닭이 없다
 * @param timeZone 켰으면 저장한 시간대, 아니면 기본 시간대
 * @param lastCheckedAt 마지막 확인 시각. 켜고 아직 확인하지 않았으면 null
 * @param lastResult 마지막 확인의 결과
 * @param dailyLimitReached 오늘 그 시간대의 사건 실행 상한에 닿았다
 * @param failing 연결 문제로 정한 횟수 이상 연달아 실패했다. 다시 연결해야 한다
 */
public record EventWatchView(
    String connectorId,
    String title,
    String noun,
    boolean enabled,
    boolean available,
    List<EventWatchBlocker> blockers,
    String timeZone,
    Instant lastCheckedAt,
    EventWatchResult lastResult,
    int lastNewCount,
    boolean dailyLimitReached,
    boolean failing) {

    public EventWatchView {
        blockers = List.copyOf(blockers);
    }
}
