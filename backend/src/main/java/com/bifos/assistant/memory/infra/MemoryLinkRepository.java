package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryLinkEnds;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.memory.domain.type.MemoryLinkType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 기억 사이의 연결이다. 「대체됨」 판정은 {@code (to_memory_id, type, status)} 색인을 쓴다. */
public interface MemoryLinkRepository extends JpaRepository<MemoryLink, Long> {

    /**
     * 받아들이기와 거절, 되돌리기가 같은 줄을 함께 바꾸지 않게 쓰기 잠금으로 읽는다. 둘째 요청은 첫째가 바꾼 상태를 읽고 409 를 받는다. 항목을 지우는
     * 쪽은 이 줄을 지우려다 기다리므로, 받아들이는 중에 줄이 사라져 갱신이 0행이 되는 일이 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from MemoryLink l where l.id = :id")
    Optional<MemoryLink> findByIdForUpdate(@Param("id") Long id);


    /** 연결의 양 끝 번호만 읽는다. 엔티티를 영속 문맥에 올리지 않아 뒤의 잠금 조회가 최신 상태를 읽는다. */
    @Query("select new com.bifos.assistant.memory.domain.MemoryLinkEnds(l.fromMemoryId, l.toMemoryId)"
        + " from MemoryLink l where l.id = :id")
    Optional<MemoryLinkEnds> findEndsById(@Param("id") Long id);


    Optional<MemoryLink> findByFromMemoryIdAndToMemoryIdAndType(Long fromMemoryId, Long toMemoryId, MemoryLinkType type);


    boolean existsByToMemoryIdAndTypeAndStatus(Long toMemoryId, MemoryLinkType type, MemoryLinkStatus status);


    List<MemoryLink> findByToMemoryIdInAndTypeAndStatus(
        Collection<Long> toMemoryIds, MemoryLinkType type, MemoryLinkStatus status);


    long countByCreatedByExecutionId(Long executionId);


    /** 그 사용자가 주인인 개인 항목을 옛 항목으로 둔 대체 제안이다. */
    @Query("select l from MemoryLink l, Memory m"
        + " where m.id = l.toMemoryId"
        + " and l.type = com.bifos.assistant.memory.domain.type.MemoryLinkType.SUPERSEDES"
        + " and l.status = com.bifos.assistant.memory.domain.type.MemoryLinkStatus.PROPOSED"
        + " and m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.USER"
        + " and m.ownerUserId = :userId"
        + " order by l.id")
    List<MemoryLink> findProposedSupersedesOfOwner(@Param("userId") Long userId);


    /** 그 그룹의 항목을 옛 항목으로 둔 대체 제안이다. 받아들일 수 있는 사람은 그룹의 {@code ADMIN} 이다. */
    @Query("select l from MemoryLink l, Memory m"
        + " where m.id = l.toMemoryId"
        + " and l.type = com.bifos.assistant.memory.domain.type.MemoryLinkType.SUPERSEDES"
        + " and l.status = com.bifos.assistant.memory.domain.type.MemoryLinkStatus.PROPOSED"
        + " and m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.GROUP"
        + " and m.groupId = :groupId"
        + " order by l.id")
    List<MemoryLink> findProposedSupersedesOfGroup(@Param("groupId") Long groupId);


    /** 그 항목을 한쪽 끝으로 둔, 끊지 않은 줄이다. */
    @Query("select l from MemoryLink l where (l.fromMemoryId = :id or l.toMemoryId = :id)"
        + " and l.status <> com.bifos.assistant.memory.domain.type.MemoryLinkStatus.REVOKED")
    List<MemoryLink> findOpenByMemoryId(@Param("id") Long id);


    /** 항목을 지울 때 같은 트랜잭션에서 그 항목을 한쪽 끝으로 둔 줄을 모두 지운다. */
    @Modifying
    @Query("delete from MemoryLink l where l.fromMemoryId = :id or l.toMemoryId = :id")
    void deleteByMemoryId(@Param("id") Long id);
}
