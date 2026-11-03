package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryEmbeddingSource;
import com.bifos.assistant.memory.domain.MemoryEmbeddingStamp;
import com.bifos.assistant.memory.domain.MemoryEmbeddingVector;
import com.bifos.assistant.memory.domain.entity.Memory;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 기억 항목 줄의 벡터 칸({@code embedding_model}, {@code embedding_fingerprint}, {@code embedding_vector})을 읽고 쓴다(ADR-20261102 /
 * table-reduce-r2). 엔티티는 이 칸을 매핑하지 않아 native 쿼리로 다룬다. 세 칸은 함께 채우고 함께 비운다.
 *
 * <p>{@link MemoryRepository} 가 이 인터페이스를 이어받아 Spring Data 가 쿼리를 만든다. 같은 엔티티에 저장소를 둘 두면 엔티티로 저장소를 찾는
 * Spring Data 의 조회가 어느 쪽을 낼지 정해지지 않아, 벡터 쿼리만 이 이름으로 나눠 주입받는다.
 *
 * <p>임베딩 대상은 {@code ACCEPTED}, 꺼내는 방식 {@code SEARCH}, 출처 원문이 아니고, 민감도가 {@code NORMAL} 인 항목이다. 제목과 본문은 암호문이라 엔티티를 읽어 앱에서 풀고, 풀지 못한 줄은 뺀다(ADR-20261103 / memory-encryption).
 * 효력 있는 대체 연결이 가리키는 항목도 빠진다. 이미 있던 그 항목의 벡터는 다음 주기의 정리가 비운다.
 */
public interface MemoryEmbeddingRepository {

    /** 세 칸이 모두 찬 줄만 벡터가 있는 것으로 본다. 직접 고쳐 한 칸만 빈 줄이 읽기를 깨뜨리지 않게 한다. */
    String HAS_VECTOR = " embedding_model is not null and embedding_fingerprint is not null and embedding_vector is not null";


    /** 임베딩 대상 항목을 번호 순으로 읽는다. */
    @Query("select m"
        + " from Memory m"
        + " where m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED"
        + " and m.retrieval = com.bifos.assistant.memory.domain.type.MemoryRetrieval.SEARCH"
        + " and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE"
        + " and m.sensitivity = com.bifos.assistant.memory.domain.type.MemorySensitivity.NORMAL"
        + " and not exists (select 1 from MemoryLink l where l.toMemoryId = m.id"
        + " and l.type = com.bifos.assistant.memory.domain.type.MemoryLinkType.SUPERSEDES"
        + " and l.status = com.bifos.assistant.memory.domain.type.MemoryLinkStatus.ACTIVE)"
        + " order by m.id")
    List<Memory> findEmbeddableMemories();


    /** 임베딩 대상 항목의 푼 글을 번호 순으로 낸다. 풀지 못한 줄은 뺀다. 제목과 본문이 암호문이라 엔티티를 읽어 푼다. */
    default List<MemoryEmbeddingSource> findEmbeddableSources() {
        return sourcesOf(findEmbeddableMemories());
    }


    /** 그 번호 가운데 지금도 임베딩 대상인 항목을 번호 순으로 읽는다. 묶음을 보내기 직전과 쓰기 직전에 다시 읽는 데 쓴다. */
    @Query("select m"
        + " from Memory m"
        + " where m.id in :ids"
        + " and m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED"
        + " and m.retrieval = com.bifos.assistant.memory.domain.type.MemoryRetrieval.SEARCH"
        + " and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE"
        + " and m.sensitivity = com.bifos.assistant.memory.domain.type.MemorySensitivity.NORMAL"
        + " and not exists (select 1 from MemoryLink l where l.toMemoryId = m.id"
        + " and l.type = com.bifos.assistant.memory.domain.type.MemoryLinkType.SUPERSEDES"
        + " and l.status = com.bifos.assistant.memory.domain.type.MemoryLinkStatus.ACTIVE)"
        + " order by m.id")
    List<Memory> findEmbeddableMemoriesByIds(@Param("ids") Collection<Long> ids);


    /** 그 번호 가운데 지금도 임베딩 대상인 항목의 푼 글이다. */
    default List<MemoryEmbeddingSource> findEmbeddableSourcesByIds(Collection<Long> ids) {
        return sourcesOf(findEmbeddableMemoriesByIds(ids));
    }


    private static List<MemoryEmbeddingSource> sourcesOf(List<Memory> memories) {
        return memories.stream()
            .filter(Memory::readable)
            .map(memory -> new MemoryEmbeddingSource(memory.id(), memory.title(), memory.content()))
            .toList();
    }


    /**
     * 벡터가 있는 그 사용자의 개인 항목 번호다. 기억 사이의 연결을 찾을 때 같은 주인의 항목끼리만 비교한다. 벡터는 모델과 지문을 맞춰 보는
     * {@code MemoryVectorCache} 로 읽는다.
     */
    @Query(value = "select id from memory where" + HAS_VECTOR + " and scope = 'USER' and owner_user_id = :ownerUserId",
        nativeQuery = true)
    List<Long> findMemoryIdsOfOwner(@Param("ownerUserId") Long ownerUserId);


    /** 벡터가 있는 그 그룹의 그룹 항목 번호다. */
    @Query(value = "select id from memory where" + HAS_VECTOR + " and scope = 'GROUP' and group_id = :groupId",
        nativeQuery = true)
    List<Long> findMemoryIdsOfGroup(@Param("groupId") Long groupId);


    /** 벡터가 있는 줄의 번호, 모델, 지문이다. 벡터는 읽지 않는다. */
    @Query(value = "select id, embedding_model, embedding_fingerprint from memory where" + HAS_VECTOR + " order by id",
        nativeQuery = true)
    List<Object[]> findStampTuples();


    /** 그 번호 가운데 벡터가 있는 줄의 번호, 모델, 지문, 벡터다. */
    @Query(value = "select id, embedding_model, embedding_fingerprint, embedding_vector from memory"
        + " where id in (:ids) and" + HAS_VECTOR, nativeQuery = true)
    List<Object[]> findVectorTuples(@Param("ids") Collection<Long> ids);


    /**
     * 한 항목의 벡터를 넣거나 바꾼다. 항목 줄의 다른 칸과 {@code updated_at} 은 바꾸지 않는다.
     *
     * @return 바꾼 줄 수. 그 사이 항목이 지워졌으면 0
     */
    @Modifying
    @Query(value = "update memory set embedding_model = :model, embedding_fingerprint = :fingerprint, embedding_vector = :vector"
        + " where id = :memoryId", nativeQuery = true)
    int writeVector(
        @Param("memoryId") Long memoryId,
        @Param("model") String model,
        @Param("fingerprint") byte[] fingerprint,
        @Param("vector") byte[] vector);


    /** 그 항목들의 세 벡터 칸을 모두 비운다. 한 칸만 남은 줄도 함께 비운다. 비운 줄 수를 돌려준다. */
    @Modifying
    @Query(value = "update memory set embedding_model = null, embedding_fingerprint = null, embedding_vector = null"
        + " where id in (:ids)", nativeQuery = true)
    int clearVectors(@Param("ids") Collection<Long> ids);


    /** 벡터가 있는 줄의 모델과 지문이다. 번호 순이다. */
    default List<MemoryEmbeddingStamp> findStamps() {
        return findStampTuples().stream()
            .map(row -> new MemoryEmbeddingStamp(((Number)row[0]).longValue(), (String)row[1], bytes(row[2])))
            .toList();
    }


    /** 그 번호 가운데 벡터가 있는 줄이다. */
    default List<MemoryEmbeddingVector> findVectors(Collection<Long> memoryIds) {
        if (memoryIds.isEmpty()) {
            return List.of();
        }
        return findVectorTuples(memoryIds).stream()
            .map(row -> new MemoryEmbeddingVector(
                ((Number)row[0]).longValue(),
                (String)row[1],
                bytes(row[2]),
                MemoryEmbeddingVector.decode(bytes(row[3]))))
            .toList();
    }


    /** native 쿼리의 바이너리 칸은 드라이버에 따라 {@code byte[]} 나 {@link Blob} 으로 온다. */
    private static byte[] bytes(Object value) {
        if (value instanceof byte[] raw) {
            return raw;
        }
        if (value instanceof Blob blob) {
            try {
                return blob.getBytes(1, (int)blob.length());
            } catch (SQLException ex) {
                throw new IllegalStateException("cannot read memory embedding column", ex);
            }
        }
        throw new IllegalStateException("unexpected memory embedding column type: " + (value == null ? "null" : value.getClass().getName()));
    }
}
