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


    /** 그룹의 모든 사용자를 번호 순으로 읽는다. 꺼진 사용자도 든다. */
    List<AppUser> findByGroupIdOrderByIdAsc(Long groupId);


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


    /**
     * 사용자 정리의 마지막에 사용자 줄을 지운다(ADR-20261101 / user-purge). 지운 행 수를 돌려준다.
     *
     * <p>{@code ON DELETE CASCADE} 가 아닌 외래 키 표의 줄을 모두 지운 뒤에만 부른다. 남아 있으면 외래 키가 거절한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AppUser u where u.id = :id")
    int deletePurged(@Param("id") Long id);


    /** 평가 전용 사용자로 표시한다. 엔티티 저장이 이 칸을 덮지 않으므로 이 쿼리로만 바꾼다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AppUser u set u.evaluation = true, u.updatedAt = :at where u.id = :id and u.evaluation = false")
    int markEvaluation(@Param("id") Long id, @Param("at") Instant at);


    /** 그룹의 평가 전용 사용자다. 그룹마다 많아야 하나다. */
    Optional<AppUser> findFirstByGroupIdAndEvaluationTrueOrderByIdAsc(Long groupId);
}
