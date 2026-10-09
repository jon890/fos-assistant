package com.bifos.assistant.user.infra;

import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.domain.AppUser;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    List<AppUser> findByGroupIdAndRole(Long groupId, UserRole role);

    Optional<AppUser> findByEmail(String email);

    /**
     * 정규화한 메일 주소로 찾는다. {@code app_user.email} 은 로그인한 원문이라 대소문자와 공백이 허용 목록의
     * 정규화 주소와 다를 수 있다.
     */
    @Query("select u from AppUser u where lower(trim(u.email)) = :normalizedEmail")
    List<AppUser> findAllByNormalizedEmail(@Param("normalizedEmail") String normalizedEmail);

    boolean existsByGroupId(Long groupId);

    @Query("select distinct u.groupId from AppUser u")
    List<Long> findDistinctGroupIds();

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
