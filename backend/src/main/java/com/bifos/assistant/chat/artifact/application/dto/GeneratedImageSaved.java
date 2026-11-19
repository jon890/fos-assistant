package com.bifos.assistant.chat.artifact.application.dto;

/**
 * 저장한 그림의 결과다.
 *
 * @param title 정한 제목. 겹쳐서 번호가 붙었으면 번호까지 든다
 */
public record GeneratedImageSaved(String title) {
}
