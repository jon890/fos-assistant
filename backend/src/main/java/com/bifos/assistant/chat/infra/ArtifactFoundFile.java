package com.bifos.assistant.chat.infra;

/**
 * 폴더 안에서 찾은 파일 하나다.
 *
 * @param path 대화 폴더 안의 상대 경로. {@code /} 로 나눈다
 * @param byteSize 찾았을 때의 크기
 */
public record ArtifactFoundFile(String path, long byteSize) {}
