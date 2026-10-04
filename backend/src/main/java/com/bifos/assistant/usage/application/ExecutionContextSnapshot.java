package com.bifos.assistant.usage.application;

import java.util.List;

/**
 * 실행을 시작할 때 함께 적는 실행 당시의 상태. 모르는 값은 null 이다.
 *
 * <p>{@code instructionsHash} 는 그 실행에 넣은 {@code instructions} 의 SHA-256 앞 16바이트다.
 * 본문은 개인 Memory 를 담고 있어 어느 칸에도 저장하지 않는다.
 *
 * <p>{@code runtimeFingerprint} 는 아직 값을 얻는 경로가 없어 항상 null 로 들어온다.
 *
 * <p>{@code contextOmittedItems} 는 자리가 없어 그 실행의 문맥에서 빠진 Memory 항목 수다.
 *
 * <p>{@code sources} 는 그 실행의 문맥에 실은 항목의 참조를 실은 순서대로 담는다. null 이면 빈 목록이다(ADR-071).
 */
public record ExecutionContextSnapshot(
        Long contextChars,
        String runtimeFingerprint,
        String instructionsHash,
        Integer contextOmittedItems,
        List<ContextSourceRef> sources) {

    public ExecutionContextSnapshot {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 실은 항목의 참조를 남기지 않는 경로가 쓴다. */
    public ExecutionContextSnapshot(
            Long contextChars, String runtimeFingerprint, String instructionsHash, Integer contextOmittedItems) {
        this(contextChars, runtimeFingerprint, instructionsHash, contextOmittedItems, List.of());
    }

    /** 빠진 항목 수를 모르는 경로가 쓴다. */
    public ExecutionContextSnapshot(Long contextChars, String runtimeFingerprint, String instructionsHash) {
        this(contextChars, runtimeFingerprint, instructionsHash, null, List.of());
    }

    /** 남길 상태가 글자 수뿐일 때 쓴다. */
    public static ExecutionContextSnapshot ofChars(Long contextChars) {
        return new ExecutionContextSnapshot(contextChars, null, null, null, List.of());
    }
}
