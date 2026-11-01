package com.bifos.assistant.user.infra;

import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.domain.AppUser;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AppUser u set u.modelDefaultTier = :tier, u.updatedAt = :at
            where u.id = :userId and (u.modelDefaultTier <> :tier
                or (u.modelDefaultTier is null and :tier is not null)
                or (u.modelDefaultTier is not null and :tier is null))
            """)
    int updateModelDefaultTier(@Param("userId") Long userId, @Param("tier") String tier, @Param("at") Instant at);


    /** 결과물 자동 보관 설정이다. 고른 적이 없거나 없는 사용자면 null 이다. */
    @Query("select u.artifactAutoVault from AppUser u where u.id = :userId")
    Boolean findArtifactAutoVault(@Param("userId") Long userId);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "update app_user set artifact_auto_vault = :enabled, updated_at = :at where id = :userId",
        nativeQuery = true)
    int updateArtifactAutoVault(
        @Param("userId") Long userId, @Param("enabled") boolean enabled, @Param("at") Instant at);
}
