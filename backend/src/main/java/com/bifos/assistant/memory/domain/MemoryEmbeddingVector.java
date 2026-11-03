package com.bifos.assistant.memory.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 기억 항목 줄의 {@code embedding_*} 칸에 저장한 벡터다(ADR-20261015 / memory-hybrid-selection, ADR-20261102 / table-reduce-r2).
 *
 * <p>벡터는 float 을 little-endian 4바이트씩 이어 저장한다. {@code textFingerprint} 는 모델 이름과 임베딩한 글의 SHA-256 이다. 항목의
 * 글이나 모델이 바뀌면 지문이 달라져 다음 채우기가 바꾼다.
 */
public record MemoryEmbeddingVector(Long memoryId, String model, byte[] textFingerprint, float[] vector) {

    /** 저장할 바이트로 바꾼다. */
    public static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }


    /** 저장한 바이트를 벡터로 되돌린다. */
    public static float[] decode(byte[] stored) {
        ByteBuffer buffer = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[stored.length / Float.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }


    /** 벡터와 지문을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemoryEmbeddingVector[memoryId=" + memoryId + ", model=" + model + "]";
    }
}
