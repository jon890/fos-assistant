package com.bifos.assistant.quality.application.dto;

import java.util.List;

/**
 * 기능 하나를 확인한 뒤 모은 값이다. 판정은 이 값만 읽는다.
 *
 * @param turns 기능이 돈 turn 차례대로. 사진은 올리는 turn 과 묻는 turn, 위임은 사용자 turn 과 결과를 전하는 자동 turn 이다
 * @param vaultRows 그 대화로 파일 공간에 넣은 보관 줄 수
 * @param delivered 위임 결과 전달이 끝났는가
 * @param taskRunStatus 예약 작업 발화 줄의 상태 이름. 없으면 null
 * @param taskRunExecutionId 그 발화의 실행 번호. 없으면 null
 * @param timedOut 기능 마감을 넘겼는가
 */
public record CheckObservation(
    List<TurnObservation> turns,
    int vaultRows,
    boolean delivered,
    String taskRunStatus,
    Long taskRunExecutionId,
    boolean timedOut) {

    public CheckObservation {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
