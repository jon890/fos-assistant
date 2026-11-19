package com.bifos.assistant.chat.artifact.application.dto;

import java.util.UUID;

/**
 * 에이전트가 대화 폴더에 본문 결과물을 쓸 때 받는 값이다.
 *
 * @param title HTML 의 제목. 보관할 때 「내 파일」 의 제목이 된다. 없으면 null
 * @param description HTML 의 한 줄 설명. 없으면 null
 */
public record ArtifactWriteRequest(
    UUID conversationId, String path, String content, String sourceUrl, String title, String description) {
}
