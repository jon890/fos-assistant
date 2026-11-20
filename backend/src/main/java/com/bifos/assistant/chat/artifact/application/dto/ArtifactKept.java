package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 「내 파일」 에 넣은 결과다.
 *
 * @param item 넣은 보관본. 이미 있었으면 그 보관본이다
 * @param already 같은 대화·경로·본문의 살아 있는 보관본이 이미 있어 새로 만들지 않았다
 */
public record ArtifactKept(ArtifactVaultItem item, boolean already) {
}
