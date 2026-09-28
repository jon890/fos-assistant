package com.bifos.assistant.chat.application;

/** 결과물 본문을 안전하게 바꾼 뒤 돌려주는 상대 경로와 저장 크기다. */
public record ArtifactWriteResult(String path, long byteSize) {
}
