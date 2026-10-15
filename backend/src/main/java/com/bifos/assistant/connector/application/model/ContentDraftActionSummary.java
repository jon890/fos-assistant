package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import java.util.UUID;

/**
 * 원고 저장 승인 줄에 붙이는 요약이다. 승인자·바인딩 판·파일 경로·ticket 은 담지 않는다.
 *
 * @param conversationId 원래 대화의 공개 ID
 */
public record ContentDraftActionSummary(UUID snapshotId, UUID draftId, long revision, UUID conversationId,
    ContentDraftJobStatus status, String stage, boolean saveClicked, String errorCode,
    String errorReason) {
}
