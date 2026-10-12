package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.domain.entity.ContentDraftSnapshot;
import com.bifos.assistant.connector.domain.ConnectorAction;

/** {@link ContentDraftJobLocks} 가 같은 트랜잭션에서 잠근 승인 줄과 snapshot, job 이다. */
record ContentDraftLockedJob(ConnectorAction action, ContentDraftSnapshot snapshot, ContentDraftJob job) {}
