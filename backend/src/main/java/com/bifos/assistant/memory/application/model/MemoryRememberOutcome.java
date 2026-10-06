package com.bifos.assistant.memory.application.model;

/** {@code memory_remember} 한 번의 결과다. 도구 결과 글은 {@code docs/backend/memory.md} 의 표가 갖는다(ADR-091). */
public enum MemoryRememberOutcome {
    /** 바로 저장해 새 항목을 만들었다. */
    REMEMBERED,
    /** 바로 저장해 기존 항목을 고쳤다. */
    UPDATED,
    /** 제안으로 남겼다. */
    PROPOSED,
    /** 같은 제목과 본문이 이미 저장돼 있다. */
    ALREADY_KNOWN,
    /** 같은 제목과 본문의 제안이 이미 있다. */
    ALREADY_PROPOSED,
    /** 같은 제목과 본문을 사용자가 거절했다. */
    REJECTED_BEFORE,
    /** 한 실행의 상한에 닿았다. */
    TOO_MANY,
    /** 그 에이전트가 받지 않는 collection 이다. */
    COLLECTION_NOT_ALLOWED,
    /** 고칠 항목을 찾지 못했다. 없거나, 남의 것이거나, 이 에이전트가 받지 않거나, 승인 전이거나, 민감 항목이다. */
    UPDATE_TARGET_NOT_FOUND,
    /** 바로 저장 조건이 아니라 기존 항목을 고치지 않았다. 사용자에게 확인해야 한다. */
    UPDATE_NEEDS_CONFIRMATION,
    /** 민감 제안을 암호화할 key 가 없다. */
    ENCRYPTION_UNAVAILABLE
}
