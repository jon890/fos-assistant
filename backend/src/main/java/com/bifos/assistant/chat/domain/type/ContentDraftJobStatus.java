package com.bifos.assistant.chat.domain.type;

/** 원고 revision 상태와 분리한 저장 이력의 상태다. */
public enum ContentDraftJobStatus {
    PREVIEW_READY, DISPATCHED, SAVING, SUCCEEDED, FAILED, UNKNOWN, STALE
}
