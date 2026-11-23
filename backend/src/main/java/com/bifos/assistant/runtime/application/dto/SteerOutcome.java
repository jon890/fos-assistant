package com.bifos.assistant.runtime.application.dto;

/**
 * 도는 실행에 글을 끼워 넣으라고 보낸 결과다({@code POST /v1/runs/{run_id}/steer}).
 *
 * <p>받음은 Hermes 가 그 글을 슬롯에 넣어 두었다는 뜻이지 모델이 읽었다는 뜻이 아니다. Hermes 는 읽었다는 사건을 주지
 * 않는다(ADR-20261116 / run-steer).
 */
public enum SteerOutcome {
    /** 200 과 {@code accepted: true}. */
    ACCEPTED,
    /** Hermes 가 4xx·5xx 로 받지 않았다고 답했다. 실행이 {@code running} 이 아니거나 글이 비었거나 agent 가 거절했다. */
    REJECTED,
    /** 응답을 받지 못했다(시간 초과, 연결 실패, 읽지 못한 응답). Hermes 가 받았는지 알 수 없다. */
    UNKNOWN
}
