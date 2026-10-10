package com.bifos.assistant.chat.application.model;

import java.util.List;

/** 빈 첨부 목록은 대화 전체다. 사용자 잠금 뒤 변경 대상을 고정한다. */
public record ChatContentMutationTarget(Long conversationId, List<Long> attachmentIds) {
    public ChatContentMutationTarget {
        attachmentIds = attachmentIds.stream().distinct().sorted().toList();
    }
}
