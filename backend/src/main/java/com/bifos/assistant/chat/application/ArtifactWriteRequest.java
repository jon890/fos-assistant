package com.bifos.assistant.chat.application;

import java.util.UUID;

/** 에이전트가 대화 폴더에 본문 결과물을 쓸 때 받는 값이다. */
public record ArtifactWriteRequest(UUID conversationId, String path, String content, String sourceUrl) {
}
