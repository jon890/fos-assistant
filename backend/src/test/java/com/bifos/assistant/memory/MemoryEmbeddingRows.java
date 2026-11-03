package com.bifos.assistant.memory;

import com.bifos.assistant.memory.domain.MemoryEmbeddingVector;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트가 기억 항목 줄의 벡터 칸({@code embedding_*})을 직접 넣고 읽는다. 운영 코드는 이 칸을 {@code MemoryEmbeddingRepository} 로만 다룬다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MemoryEmbeddingRows {

    /** 모든 항목의 벡터를 비운다. */
    public static void clearAll(JdbcTemplate jdbc) {
        jdbc.update("UPDATE memory SET embedding_model = NULL, embedding_fingerprint = NULL, embedding_vector = NULL"
            + " WHERE embedding_model IS NOT NULL");
    }


    /** 한 항목의 벡터를 넣는다. */
    public static void write(JdbcTemplate jdbc, Long memoryId, String model, byte[] fingerprint, float[] vector) {
        int updated = jdbc.update(
            "UPDATE memory SET embedding_model = ?, embedding_fingerprint = ?, embedding_vector = ? WHERE id = ?",
            model,
            fingerprint,
            MemoryEmbeddingVector.encode(vector),
            memoryId);
        if (updated != 1) {
            throw new IllegalStateException("no memory row " + memoryId);
        }
    }


    /** 벡터가 있는 항목 번호다. 번호 순이다. */
    public static List<Long> memoryIds(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT id FROM memory WHERE embedding_model IS NOT NULL ORDER BY id", Long.class);
    }


    /** 벡터가 있는 항목 수다. */
    public static int count(JdbcTemplate jdbc) {
        return memoryIds(jdbc).size();
    }


    /** 한 항목의 벡터다. 없으면 비어 있다. */
    public static Optional<MemoryEmbeddingVector> find(JdbcTemplate jdbc, Long memoryId) {
        return jdbc.query(
            "SELECT id, embedding_model, embedding_fingerprint, embedding_vector FROM memory WHERE id = ? AND embedding_model IS NOT NULL",
            (rs, rowNum) -> new MemoryEmbeddingVector(
                rs.getLong(1),
                rs.getString(2),
                rs.getBytes(3),
                MemoryEmbeddingVector.decode(rs.getBytes(4))),
            memoryId).stream().findFirst();
    }
}
