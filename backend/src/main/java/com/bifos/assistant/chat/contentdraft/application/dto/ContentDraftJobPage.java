package com.bifos.assistant.chat.contentdraft.application.dto;

import java.util.List;
import java.util.UUID;

/** 원고 저장 job 한 페이지다. 페이지 끝이면 cursor는 null이다. */
public record ContentDraftJobPage(List<ContentDraftJobView> items, UUID nextBeforeSnapshotId) {
    public ContentDraftJobPage {
        items = List.copyOf(items);
    }
}
