package com.bifos.assistant.chat.artifact.domain.type;

/**
 * 결과물 행 하나의 자동 보관 상태다(#530 PR 3). 비어 있으면 자동 보관 대상이 아니다.
 *
 * <p>{@code PENDING} 과 {@code RUNNING} 은 재시작 뒤에 다시 돈다. 나머지는 끝난 상태다.
 */
public enum AutoVaultState {
    /** 보관할 차례를 기다린다. */
    PENDING,
    /** 한 작업이 맡아 보관하는 중이다. 재시작하면 맡은 작업이 없으므로 다시 {@code PENDING} 으로 돌린다. */
    RUNNING,
    /** 보관했거나 같은 보관본이 이미 있다. */
    DONE,
    /** 사용자가 같은 보관본을 지운 적이 있어 다시 만들지 않았다. */
    SKIPPED,
    /** 보관하지 못했다. 까닭은 오류 코드 칸에 있고 사용자가 다시 보관할 수 있다. */
    FAILED
}
