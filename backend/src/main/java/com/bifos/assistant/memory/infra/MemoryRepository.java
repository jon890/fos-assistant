package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCollectionCount;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryRepository extends JpaRepository<Memory, Long>, JpaSpecificationExecutor<Memory> {

    Optional<Memory> findByProposalDedupKey(String proposalDedupKey);

    List<Memory> findByScopeAndOwnerUserIdAndEntryTypeOrderByCollectionAscDocumentKeyAsc(
            MemoryScope scope, Long ownerUserId, MemoryEntryType entryType);

    List<Memory> findByScopeAndOwnerUserIdAndStatusOrderByIdAsc(
            MemoryScope scope, Long ownerUserId, MemoryStatus status);

    Optional<Memory> findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(
            MemoryScope scope, Long ownerUserId, String collection, String documentKey);

    /** 암호화되지 않은 채 남은 줄의 번호를 찾는다. 잠그지 않고 읽으므로 쓰기 전에 잠가 다시 읽는다(ADR-055). */
    @Query("select m.id from Memory m where m.sensitivity = :sensitivity and m.contentKeyId is null")
    List<Long> findIdsBySensitivityAndContentKeyIdIsNull(@Param("sensitivity") MemorySensitivity sensitivity);

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
     * 한 사용자와 그 그룹의 실릴 수 있는 항목을 collection 과 민감도마다 센다.
     *
     * <p>실릴 수 있는 항목은 {@code ACCEPTED} 이고 {@code SOURCE} 도 {@code ARCHIVE} 도 아닌 줄이다. collection 과
     * 민감도는 거르지 않는다. 범위는 그 사용자의 {@code USER} 항목과 그 그룹의 {@code GROUP} 항목이다.
     *
     * @param userId 항목 주인이다. null 이면 {@code USER} 조건이 참이 되지 않아 그룹 항목만 센다
     * @param groupId 셀 그룹이다
     */
    @Query("select new com.bifos.assistant.memory.domain.MemoryCollectionCount(m.collection, m.sensitivity, count(m))"
            + " from Memory m"
            + " where m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED"
            + " and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE"
            + " and m.retrieval <> com.bifos.assistant.memory.domain.type.MemoryRetrieval.ARCHIVE"
            + " and ((m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.USER and m.ownerUserId = :userId)"
            + " or (m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.GROUP and m.groupId = :groupId))"
            + " group by m.collection, m.sensitivity")
    List<MemoryCollectionCount> countLoadableByCollection(@Param("userId") Long userId, @Param("groupId") Long groupId);
}
