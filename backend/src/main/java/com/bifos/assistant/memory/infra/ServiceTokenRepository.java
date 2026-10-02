package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.ServiceToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceTokenRepository extends JpaRepository<ServiceToken, Long> {

    Optional<ServiceToken> findByTokenHash(String tokenHash);

    List<ServiceToken> findByUserIdOrderByIdDesc(Long userId);

    /**
     * 마지막 사용 시각만 적는다. 엔티티를 통째로 저장하면 그 사이에 커밋된 폐기를 옛 값으로 되돌린다.
     */
    @Modifying
    @Query("update ServiceToken t set t.lastUsedAt = :at where t.id = :id")
    int markUsed(@Param("id") Long id, @Param("at") Instant at);

    /**
     * 폐기 시각만 적는다. 이미 폐기된 줄은 건드리지 않아 처음 시각이 남고, 실행 순서와 상관없이 폐기가 이긴다.
     */
    @Modifying
    @Query("update ServiceToken t set t.revokedAt = :at where t.id = :id and t.revokedAt is null")
    int revoke(@Param("id") Long id, @Param("at") Instant at);

    /** 한 사용자의 폐기되지 않은 토큰을 모두 폐기하고 그 수를 낸다. */
    @Modifying
    @Query("update ServiceToken t set t.revokedAt = :at where t.userId = :userId and t.revokedAt is null")
    int revokeAllOf(@Param("userId") Long userId, @Param("at") Instant at);
}
