package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.MemoryCollectionCount;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryRepository extends JpaRepository<Memory, Long>, JpaSpecificationExecutor<Memory>, MemoryEmbeddingRepository {

    /** 정비 규칙이 읽을 항목이다. 종류와 승인 상태가 같은 줄을 번호 순서로 낸다. */
    List<Memory> findByEntryTypeAndStatusOrderByIdAsc(MemoryEntryType entryType, MemoryStatus status);


    Optional<Memory> findByProposalDedupKey(String proposalDedupKey);


    /** 엔티티를 먼저 읽지 않고 중복 후보의 번호만 찾는다. 쓰기 전에 잠가 다시 읽는다. */
    @Query("select m.id from Memory m where m.proposalDedupKey = :proposalDedupKey")
    Optional<Long> findIdByProposalDedupKey(@Param("proposalDedupKey") String proposalDedupKey);


    List<Memory> findByScopeAndOwnerUserIdAndEntryTypeOrderByCollectionAscDocumentKeyAsc(
        MemoryScope scope, Long ownerUserId, MemoryEntryType entryType);


    List<Memory> findByScopeAndOwnerUserIdAndStatusOrderByIdAsc(
        MemoryScope scope, Long ownerUserId, MemoryStatus status);


    Optional<Memory> findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(
        MemoryScope scope, Long ownerUserId, String collection, String documentKey);


    /** 데이터 key 로 옮기지 않은 줄의 번호를 찾는다. 잠그지 않고 읽으므로 쓰기 전에 잠가 다시 읽는다(ADR-20261103 / memory-encryption). */
    @Query("select m.id from Memory m where m.dataKeyId is null order by m.id")
    List<Long> findIdsWithoutDataKey();


    /** 제안 중복 키가 있는 줄의 번호다. 기동할 때 그 키를 주인 key 의 HMAC 으로 바꾸는 데 쓴다(ADR-20261103 / memory-sensitivity-removal). */
    @Query("select m.id from Memory m where m.proposalDedupKey is not null order by m.id")
    List<Long> findIdsWithProposalDedupKey();


    /**
     * 항목 한 줄을 쓰기 잠금으로 읽는다.
     *
     * <p>고치거나 지울 때 그 전의 값을 판으로 남기는데, 두 요청이 같은 판 번호를 읽으면 같은 판을 두 번 남기려 한다.
     * 한 번에 하나씩 돌려 둘째가 첫째가 올린 판 번호를 읽게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Memory m where m.id = :id")
    Optional<Memory> findByIdForUpdate(@Param("id") Long id);


    /**
     * 한 사용자와 그 그룹의 실릴 수 있는 항목을 collection 마다 센다.
     *
     * <p>실릴 수 있는 항목은 {@code ACCEPTED} 이고 {@code SOURCE} 도 {@code ARCHIVE} 도 아니며 대체되지 않은({@code superseded_by_memory_id} 가 빈) 줄이다. collection 은
     * 거르지 않는다. 범위는 그 사용자의 {@code USER} 항목과 그 그룹의 {@code GROUP} 항목이다.
     *
     * @param userId 항목 주인이다. null 이면 {@code USER} 조건이 참이 되지 않아 그룹 항목만 센다
     * @param groupId 셀 그룹이다
     */
    @Query("select new com.bifos.assistant.memory.domain.MemoryCollectionCount(m.collection, count(m))"
        + " from Memory m"
        + " where m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED"
        + " and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE"
        + " and m.retrieval <> com.bifos.assistant.memory.domain.type.MemoryRetrieval.ARCHIVE"
        + " and m.supersededByMemoryId is null"
        + " and ((m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.USER and m.ownerUserId = :userId)"
        + " or (m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.GROUP and m.groupId = :groupId))"
        + " group by m.collection")
    List<MemoryCollectionCount> countLoadableByCollection(@Param("userId") Long userId, @Param("groupId") Long groupId);


    /** 그 사용자의 개인 기억 번호다. 사용자 정리가 딸린 줄을 지우는 데 쓴다(ADR-20261101 / user-purge). */
    @Query("select m.id from Memory m where m.ownerUserId = :userId and m.scope = :scope")
    List<Long> findIdsByOwnerAndScope(@Param("userId") Long userId, @Param("scope") MemoryScope scope);


    /** 사용자 정리 때 그 번호의 기억 항목을 지운다. 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Memory m where m.id in :ids")
    int deleteAllByIds(@Param("ids") Collection<Long> ids);


    /**
     * 그 항목을 대신한 것으로 적힌 옛 항목의 칸을 비운다. 대신한 새 항목이 지워지거나 승인을 벗어나면 옛 항목이 다시 실리게 한다. 칸은 엔티티가
     * 쓰지 않아 native 쿼리로 바꾼다.
     *
     * @return 비운 줄 수
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update memory set superseded_by_memory_id = null where superseded_by_memory_id = :newerId", nativeQuery = true)
    int clearSupersededBy(@Param("newerId") Long newerId);


    /**
     * 옛 연결 테이블 {@code memory_link} 에서 그 항목들을 한쪽 끝으로 둔 줄을 지운다. 코드는 옛 테이블을 읽고 쓰지 않지만, 직전 이미지가 남긴
     * 줄(자유 글 label 이 있을 수 있다)을 항목 삭제와 사용자 정리의 약속대로 지운다. 옛 테이블을 지우는 PR 이 이 메서드를 걷어 낸다(ADR-20261102 /
     * table-reduce-r2).
     *
     * @return 지운 줄 수
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "delete from memory_link where from_memory_id in (:memoryIds) or to_memory_id in (:memoryIds)", nativeQuery = true)
    int deleteLegacyLinks(@Param("memoryIds") Collection<Long> memoryIds);
}
