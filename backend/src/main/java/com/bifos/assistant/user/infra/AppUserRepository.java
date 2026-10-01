package com.bifos.assistant.user.infra;

import com.bifos.assistant.user.domain.AppUser;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByGroupId(Long groupId);

    /**
     * 사용자 한 줄을 쓰기 잠금으로 읽는다.
     *
     * <p>그 사용자의 에이전트 수를 세고 새 에이전트를 저장하는 일을 한 번에 하나씩 돌리려고 쓴다. 같은 사람이
     * 두 번 눌러도 둘째 요청은 첫째가 끝날 때까지 기다린 뒤 센다. 대기 시간을 0 으로 두지 않는 것은 곧바로
     * 거절하면 상한이 아닌 잠금 오류가 화면에 가기 때문이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("update AppUser u set u.modelDefaultTier = :tier where u.id = :userId")
    int updateModelDefaultTier(@Param("userId") Long userId, @Param("tier") String tier);

}
