package com.bifos.assistant.chat.artifact.application.dto;

import java.util.UUID;

/**
 * 「내 파일」 목록의 한 줄이다. 보관본과 그 파일이 나온 대화다.
 *
 * @param conversationId 대화의 공개 식별자. 대화를 지웠으면 null 이다
 * @param conversationTitle 대화 제목. 대화를 지웠으면 null 이다
 */
public record ArtifactVaultEntry(ArtifactVaultItem item, UUID conversationId, String conversationTitle) {
}
