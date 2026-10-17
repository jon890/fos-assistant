package com.bifos.assistant.connector.contentdraft.application.dto;

import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftJobStatus;

/** 검증한 worker projection이다. 본문과 ticket은 포함하지 않는다. */
public record ContentDraftWorkerStatus(ContentDraftJobStatus status, String stage, boolean saveClicked, String errorCode,
    String errorReason) {
}
