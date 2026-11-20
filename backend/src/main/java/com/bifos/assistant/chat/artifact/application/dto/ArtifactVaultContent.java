package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 복호화한 보관 본문 하나다.
 *
 * @param name 내려받을 때 쓰는 파일 이름
 * @param contentType {@code VaultFileType} 이 정한 형식. 글자 집합은 붙이지 않는다
 * @param inline 화면에서 그대로 열어도 되는가. 거짓이면 늘 내려받기로 준다
 */
public record ArtifactVaultContent(String name, String contentType, boolean inline, byte[] body) {
}
