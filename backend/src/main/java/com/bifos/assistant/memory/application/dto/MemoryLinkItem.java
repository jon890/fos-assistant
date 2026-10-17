package com.bifos.assistant.memory.application.dto;

import java.time.Instant;

/**
 * 연결을 찾을 때 견주는 항목 하나다. 같은 주인의 임베딩 대상 항목만 담는다.
 *
 * @param updatedAt 항목을 마지막으로 고친 시각. 대체의 방향을 정한다
 * @param text 제목과 본문을 이은 글. 값이 다른지 볼 때만 쓰고 로그에 남기지 않는다
 */
public record MemoryLinkItem(Long id, Instant updatedAt, String text, float[] vector) {

    /** 두 벡터의 코사인 유사도다. 차원이 다르거나 길이가 0 이면 0 이다. */
    public double similarity(MemoryLinkItem other) {
        float[] a = vector;
        float[] b = other.vector;
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / Math.sqrt(normA * normB);
    }
}
