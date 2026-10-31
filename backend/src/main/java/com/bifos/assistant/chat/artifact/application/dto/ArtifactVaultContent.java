package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 복호화한 보관 본문이다.
 *
 * @param name 내려받을 때 쓰는 파일 이름
 * @param contentType {@code text/html} 또는 {@code text/css}
 */
public record ArtifactVaultContent(String name, String contentType, byte[] body) {
}
