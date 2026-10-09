package com.bifos.assistant.workspace.application.model;

/**
 * 확장자 하나의 미리보기 형식이다.
 *
 * @param maxBytes 미리보기로 줄 수 있는 가장 큰 크기
 * @param html HTML 미리보기다
 */
public record WorkspacePreview(String contentType, long maxBytes, boolean html) {}
