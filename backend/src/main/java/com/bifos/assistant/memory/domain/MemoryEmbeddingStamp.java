package com.bifos.assistant.memory.domain;

/** 저장한 벡터가 어느 모델의 어느 글에서 왔는지다. 벡터는 읽지 않는다. */
public record MemoryEmbeddingStamp(Long memoryId, String model, byte[] textFingerprint) {
}
