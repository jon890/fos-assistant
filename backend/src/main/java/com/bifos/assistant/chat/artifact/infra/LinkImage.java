package com.bifos.assistant.chat.artifact.infra;

/**
 * 링크에서 받은 사진 하나다. 저장하지 않고 받기 경로의 응답으로만 쓴다.
 *
 * @param contentType {@code image/png}, {@code image/jpeg}, {@code image/webp}, {@code image/gif} 가운데 하나
 * @param bytes 받은 바이트 그대로
 */
public record LinkImage(String contentType, byte[] bytes) {
}
