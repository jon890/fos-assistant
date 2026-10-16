package com.bifos.assistant.memory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 기억 항목 하나의 임베딩 벡터다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>벡터는 float 을 little-endian 4바이트씩 이어 저장한다. {@code textFingerprint} 는 모델 이름과 임베딩한 글의 SHA-256 이다. 항목의 글이나
 * 모델이 바뀌면 지문이 달라져 다음 채우기가 바꾼다.
 */
@Entity
@Table(name = "memory_embedding")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryEmbedding {

    @Id
    @Column(name = "memory_id")
    private Long memoryId;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false)
    private int dimensions;

    @Column(name = "text_fingerprint", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] textFingerprint;

    @Getter(AccessLevel.NONE)
    @Column(nullable = false, columnDefinition = "BLOB")
    private byte[] vector;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;


    public static MemoryEmbedding of(Long memoryId, String model, byte[] fingerprint, float[] vector, Instant now) {
        MemoryEmbedding embedding = new MemoryEmbedding();
        embedding.memoryId = memoryId;
        embedding.createdAt = now;
        embedding.replace(model, fingerprint, vector, now);
        return embedding;
    }


    /** 다른 모델이나 바뀐 글의 벡터로 바꾼다. */
    public void replace(String model, byte[] fingerprint, float[] vector, Instant now) {
        this.model = model;
        this.textFingerprint = fingerprint.clone();
        this.dimensions = vector.length;
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        this.vector = buffer.array();
        this.updatedAt = now;
    }


    public float[] vector() {
        ByteBuffer buffer = ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[vector.length / Float.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }


    /** 벡터와 지문을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemoryEmbedding[memoryId=" + memoryId + ", model=" + model + "]";
    }
}
