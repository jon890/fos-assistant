package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 복호화한 보관 본문 하나다.
 *
 * @param name 내려받을 때 쓰는 파일 이름
 * @param contentType {@code text/html}, {@code text/css}, 사진 형식 가운데 하나. 글자 집합은 붙이지 않는다
 */
public record ArtifactVaultContent(String name, String contentType, byte[] body) {
}
