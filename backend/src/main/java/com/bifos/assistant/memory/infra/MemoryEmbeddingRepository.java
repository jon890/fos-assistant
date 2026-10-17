package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryEmbeddingSource;
import com.bifos.assistant.memory.domain.MemoryEmbeddingStamp;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 기억 항목의 벡터다. 임베딩 대상은 {@code ACCEPTED}, 꺼내는 방식 {@code SEARCH}, 출처 원문이 아니고, 민감도가 {@code NORMAL} 이며 암호화하지 않은 항목이다.
 * 효력 있는 대체 연결이 가리키는 항목도 빠진다. 이미 있던 그 항목의 벡터는 다음 주기의 정리가 지운다.
 */
public interface MemoryEmbeddingRepository extends JpaRepository<MemoryEmbedding, Long> {

    List<MemoryEmbedding> findByMemoryIdIn(Collection<Long> memoryIds);


    /** 항목을 지울 때 같은 트랜잭션에서 그 벡터를 지운다. */
    @Modifying
    @Query("delete from MemoryEmbedding e where e.memoryId = :memoryId")
    void deleteByMemoryId(@Param("memoryId") Long memoryId);


    /** 임베딩 대상 항목의 글을 번호 순으로 읽는다. */
    @Query("select new com.bifos.assistant.memory.domain.MemoryEmbeddingSource(m.id, m.title, m.content)"
        + " from Memory m"
        + " where m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED"
        + " and m.retrieval = com.bifos.assistant.memory.domain.type.MemoryRetrieval.SEARCH"
        + " and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE"
        + " and m.sensitivity = com.bifos.assistant.memory.domain.type.MemorySensitivity.NORMAL"
        + " and m.contentKeyId is null"
        + " and not exists (select 1 from MemoryLink l where l.toMemoryId = m.id"
        + " and l.type = com.bifos.assistant.memory.domain.type.MemoryLinkType.SUPERSEDES"
        + " and l.status = com.bifos.assistant.memory.domain.type.MemoryLinkStatus.ACTIVE)"
        + " order by m.id")
    List<MemoryEmbeddingSource> findEmbeddableSources();


    /** 그 번호 가운데 지금도 임베딩 대상인 항목의 글을 번호 순으로 읽는다. 묶음을 보내기 직전에 다시 읽는 데 쓴다. */
    @Query("select new com.bifos.assistant.memory.domain.MemoryEmbeddingSource(m.id, m.title, m.content)"
        + " from Memory m"
        + " where m.id in :ids"
        + " and m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED"
        + " and m.retrieval = com.bifos.assistant.memory.domain.type.MemoryRetrieval.SEARCH"
        + " and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE"
        + " and m.sensitivity = com.bifos.assistant.memory.domain.type.MemorySensitivity.NORMAL"
        + " and m.contentKeyId is null"
        + " and not exists (select 1 from MemoryLink l where l.toMemoryId = m.id"
        + " and l.type = com.bifos.assistant.memory.domain.type.MemoryLinkType.SUPERSEDES"
        + " and l.status = com.bifos.assistant.memory.domain.type.MemoryLinkStatus.ACTIVE)"
        + " order by m.id")
    List<MemoryEmbeddingSource> findEmbeddableSourcesByIds(@Param("ids") Collection<Long> ids);


    /** 그 사용자가 주인인 개인 항목의 벡터다. 기억 사이의 연결을 찾을 때 같은 주인의 항목끼리만 견준다. */
    @Query("select e from MemoryEmbedding e, Memory m where m.id = e.memoryId"
        + " and m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.USER"
        + " and m.ownerUserId = :ownerUserId")
    List<MemoryEmbedding> findOfOwner(@Param("ownerUserId") Long ownerUserId);


    /** 그 그룹의 그룹 항목의 벡터다. */
    @Query("select e from MemoryEmbedding e, Memory m where m.id = e.memoryId"
        + " and m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.GROUP"
        + " and m.groupId = :groupId")
    List<MemoryEmbedding> findOfGroup(@Param("groupId") Long groupId);


    /** 저장한 줄의 모델과 지문이다. 벡터는 읽지 않는다. */
    @Query("select new com.bifos.assistant.memory.domain.MemoryEmbeddingStamp(e.memoryId, e.model, e.textFingerprint)"
        + " from MemoryEmbedding e")
    List<MemoryEmbeddingStamp> findStamps();


    /** 항목이 없어졌거나 임베딩 대상에서 빠진 줄의 번호다. 대체된 항목의 줄도 여기 든다. */
    @Query("select e.memoryId from MemoryEmbedding e"
        + " where not exists (select 1 from Memory m where m.id = e.memoryId"
        + " and m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED"
        + " and m.retrieval = com.bifos.assistant.memory.domain.type.MemoryRetrieval.SEARCH"
        + " and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE"
        + " and m.sensitivity = com.bifos.assistant.memory.domain.type.MemorySensitivity.NORMAL"
        + " and m.contentKeyId is null"
        + " and not exists (select 1 from MemoryLink l where l.toMemoryId = m.id"
        + " and l.type = com.bifos.assistant.memory.domain.type.MemoryLinkType.SUPERSEDES"
        + " and l.status = com.bifos.assistant.memory.domain.type.MemoryLinkStatus.ACTIVE))")
    List<Long> findStaleMemoryIds();
}
