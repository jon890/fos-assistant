package com.bifos.assistant.chat.contentdraft.presentation;

import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftJobView;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContentDraftSnapshotDtos {

    /** 원고 job 한 페이지다. 페이지 끝이면 cursor 는 null 이다. */
    public record JobPageResponse(List<ContentDraftJobView> items, UUID nextBeforeSnapshotId) {
    }

    /** 본문·경로·revision 없이 오류 code 만 싣는다. */
    public record SnapshotError(String code) {
    }
}
