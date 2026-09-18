package com.bifos.assistant.usage.application;

/**
 * 실행을 시작할 때 함께 적는 실행 당시의 상태. 모르는 값은 null 이다.
 *
 * <p>{@code instructionsHash} 는 그 실행에 넣은 {@code instructions} 의 SHA-256 앞 16바이트다.
 * 본문은 개인 Memory 를 담고 있어 어느 칸에도 저장하지 않는다.
 *
 * <p>{@code runtimeFingerprint} 는 아직 값을 얻는 경로가 없어 항상 null 로 들어온다.
 *
 * <p>{@code contextOmittedItems} 는 자리가 없어 그 실행의 문맥에서 빠진 Memory 항목 수다.
 */
public record ExecutionContextSnapshot(
        Long contextChars,
        String runtimeFingerprint,
        String instructionsHash,
        Integer contextOmittedItems) {

    /** 빠진 항목 수를 모르는 경로가 쓴다. */
    public ExecutionContextSnapshot(Long contextChars, String runtimeFingerprint, String instructionsHash) {
        this(contextChars, runtimeFingerprint, instructionsHash, null);
    }

    /** 남길 상태가 글자 수뿐일 때 쓴다. */
    public static ExecutionContextSnapshot ofChars(Long contextChars) {
        return new ExecutionContextSnapshot(contextChars, null, null, null);
    }
}
