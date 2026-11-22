package com.bifos.assistant.connector.connection.application.dto;

import java.time.Instant;

/**
 * 운영 정기 작업이 공유 gateway 재시작을 정하는 데 쓰는 수다(ADR-20261120 / connector-auto-apply).
 *
 * @param restartWaiting 공유 gateway 재시작으로 풀릴 재시작 대기 바인딩 수. 실패 표시가 있거나 껐다 켜기 단계인 바인딩은 세지 않는다
 * @param oldestWaitingSince 그 가운데 가장 오래된 대기 시각. 없으면 null
 * @param runningExecutions 진행 중인 실행 수. 대화 turn 과 예약 실행, 끝났는지 모르는 Hermes run 을 함께 센다(스펙 DECISION13)
 */
public record ConnectorRestartSnapshot(long restartWaiting, Instant oldestWaitingSince, int runningExecutions) {
}
