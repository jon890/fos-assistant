package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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

    Optional<Memory> findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(
            MemoryScope scope, Long ownerUserId, String collection, String documentKey);

    List<Memory> findByScopeAndOwnerUserIdAndSourceTypeAndSourceRefIn(
            MemoryScope scope, Long ownerUserId, String sourceType, Collection<String> sourceRefs);

    boolean existsByScopeAndOwnerUserIdAndEntryTypeAndCollectionAndTitle(
            MemoryScope scope, Long ownerUserId, MemoryEntryType entryType, String collection, String title);

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
}
