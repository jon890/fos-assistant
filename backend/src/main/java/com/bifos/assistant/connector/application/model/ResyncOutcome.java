package com.bifos.assistant.connector.application.model;

/**
 * 바인딩의 반영 맞추기가 끝난 까닭이다. {@link #READY} 가 아니면 그 바인딩은 {@code PENDING} 이다.
 *
 * <p>저장하지 않는 서비스 결과다. 관리자 반영 완료가 이 까닭으로 오류 코드를 고르고, 외부 호출 실패가 아닌 까닭은 커넥터 번호와
 * 함께 로그 한 줄로 남는다.
 */
public enum ResyncOutcome {
    /** 쓸 수 있다. 바인딩이 {@code READY} 가 됐다. */
    READY,
    /** 재시작 대기라 다시 보내지 않았거나, 다시 보낸 설치가 재시작을 요구했다. */
    RESTART_PENDING,
    /** 반영 예정 시각 전이거나, 다시 보낸 설치가 {@code reload_pending} 이다. */
    APPLY_SCHEDULED,
    /** 카탈로그에서 빠진 커넥터다. */
    CATALOG_MISSING,
    /** 다시 읽은 설치가 꺼져 있거나 {@code configured} 가 거짓이거나 방식이 다르다. */
    NOT_INSTALLED,
    /** 다시 읽은 설치의 {@code policy_hook} 이 거짓이다. */
    POLICY_HOOK_OFF,
    /** probe 가 실패했다. */
    PROBE_FAILED,
    /** probe 가 도구를 하나도 내지 않았다. */
    NO_TOOLS,
    /** 옛 바인딩의 켜진 내장 도구가 manifest 의 선언과 다르다. */
    TOOLSETS_DIFFER,
    /** 외부 호출이 실패했다. 단계와 예외 종류를 경고로 남긴다. */
    CALL_FAILED
}
