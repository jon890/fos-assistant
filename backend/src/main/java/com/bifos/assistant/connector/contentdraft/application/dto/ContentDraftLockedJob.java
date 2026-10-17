package com.bifos.assistant.connector.contentdraft.application.dto;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftSnapshot;
import com.bifos.assistant.connector.action.domain.ConnectorAction;

/** {@link ContentDraftJobLocks} 가 같은 트랜잭션에서 잠근 승인 줄과 snapshot, job 이다. */
public record ContentDraftLockedJob(ConnectorAction action, ContentDraftSnapshot snapshot, ContentDraftJob job) {
}
