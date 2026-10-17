package com.bifos.assistant.chat.contentdraft.presentation;

import java.util.List;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftSummary;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContentDraftDtos {

    public record ListResponse(List<ContentDraftSummary> items) {
    }

    public record DraftError(String code, String message, Long currentRevision) {
    }
}
