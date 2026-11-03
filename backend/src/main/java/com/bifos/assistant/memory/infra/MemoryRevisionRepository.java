package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.entity.MemoryRevisionId;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryRevisionRepository extends JpaRepository<MemoryRevision, MemoryRevisionId> {

    /** 한 항목의 물러난 판을 오래된 것부터 낸다. 지운 항목의 번호로도 찾는다. */
    List<MemoryRevision> findByIdMemoryIdOrderByIdRevisionAsc(Long memoryId);


    /** 데이터 key 로 옮기지 않은 revision 의 번호다(ADR-20261103 / memory-encryption). */
    @Query("select r.id from MemoryRevision r where r.dataKeyId is null order by r.id.memoryId, r.id.revision")
    List<MemoryRevisionId> findIdsWithoutDataKey();


    /** revision 한 줄을 쓰기 잠금으로 읽는다. 기동할 때의 이관이 저장 모양을 바꾸기 전에 쓴다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MemoryRevision r where r.id = :id")
    Optional<MemoryRevision> findByIdForUpdate(@Param("id") MemoryRevisionId id);


    /** 사용자 정리 때 그 사용자가 주인이던 변경 이력을 모두 지운다. 그룹 기억의 이력은 주인이 비어 있어 남는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemoryRevision r where r.ownerUserId = :userId")
    int deleteAllOfOwner(@Param("userId") Long userId);
}
