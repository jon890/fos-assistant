package com.bifos.assistant.memory.application.dto;

/** 승인한 기억 지우기의 결과다(서비스 개념 스펙 REQ13). */
public enum MemoryForgetOutcome {
    /** 지웠다. */
    FORGOTTEN,
    /** 카드를 띄운 뒤 그 기억을 고쳐, 카드에 보인 내용과 달라 지우지 않았다. */
    CHANGED,
    /** 이미 없거나 지울 권한이 없어 지우지 않았다. */
    GONE
}
