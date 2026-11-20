package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 그림을 고칠 바탕으로 읽은, 그 대화에서 만든 그림의 원본이다(ADR-20261120 / image-source).
 *
 * @param contentType 파일 머리 바이트로 정한 형식
 * @param bytes 저장한 그림 바이트 그대로
 */
public record GeneratedImageSource(String contentType, byte[] bytes) {
}
