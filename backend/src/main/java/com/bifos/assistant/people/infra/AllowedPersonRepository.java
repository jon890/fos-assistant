package com.bifos.assistant.people.infra;

import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.shared.domain.type.UserRole;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllowedPersonRepository extends JpaRepository<AllowedPerson, Long> {

    /**
     * 들어와도 되는 사람을 찾는다.
     *
     * <p>메일 주소는 {@link AllowedPerson#normalizeEmail(String)} 을 지난 값이어야 한다.
     */
    Optional<AllowedPerson> findByEmailAndEnabledTrue(String email);


    /**
     * 그 주소의 줄이 꺼져 있는가. 줄이 없으면 거짓이다.
     *
     * <p>메일 주소는 {@link AllowedPerson#normalizeEmail(String)} 을 지난 값이어야 한다.
     */
    boolean existsByEmailAndEnabledFalse(String email);


    boolean existsByEmail(String email);


    /** 줄을 영속성 문맥에 올리지 않고 메일 주소만 읽는다. 사용자 행을 먼저 잠근 뒤 줄을 잠글 때 쓴다. */
    @Query("select person.email from AllowedPerson person where person.id = :id")
    Optional<String> findEmailById(@Param("id") Long id);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select person from AllowedPerson person where person.id = :id")
    Optional<AllowedPerson> findByIdForUpdate(@Param("id") Long id);


    /** 메일 주소는 {@link AllowedPerson#normalizeEmail(String)} 을 지난 값이어야 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select person from AllowedPerson person where person.email = :email")
    Optional<AllowedPerson> findByEmailForUpdate(@Param("email") String email);


    boolean existsByHermesProfile(String hermesProfile);


    @Modifying
    @Query("""
            update AllowedPerson person set person.lastLoginAt = :at
            where person.email = :email and person.enabled = true
                and (person.lastLoginAt is null or person.lastLoginAt < :at)
            """)
    int updateLastLoginAtIfNewer(@Param("email") String email, @Param("at") Instant at);


    /**
     * 꺼져 있고 정리를 cutoff 이전에 예약한 사람의 번호를 먼저 예약한 것부터 읽는다(ADR-20261101 / user-purge).
     *
     * <p>{@code skipped} 는 기다리는 간격 안의 번호다. 비면 질의가 깨지므로 부르는 쪽이 없는 번호 하나를 넘긴다.
     */
    @Query("""
            select person.id from AllowedPerson person
             where person.enabled = false and person.purgeScheduledAt is not null and person.purgeScheduledAt <= :cutoff
               and person.id not in :skipped
             order by person.purgeScheduledAt asc, person.id asc
            """)
    List<Long> findPurgeDue(@Param("cutoff") Instant cutoff, @Param("skipped") Collection<Long> skipped, Pageable page);


    /**
     * 그 그룹에서 그룹 공개 에이전트를 넘겨받을 관리자의 사용자 번호다. 정리 대상을 빼고, 허용 목록에서 켜진 {@code role} 가운데 번호가
     * 가장 작은 사람이다. 부르는 쪽은 {@code ADMIN} 을 넘긴다. 없으면 비어 있다.
     */
    @Query("""
            select min(u.id) from AppUser u, AllowedPerson p
             where p.email = lower(trim(u.email)) and p.enabled = true
               and u.groupId = :groupId and u.role = :role
               and u.id <> :excludedUserId
            """)
    Optional<Long> findAdminToTakeOver(
        @Param("groupId") Long groupId, @Param("role") UserRole role, @Param("excludedUserId") Long excludedUserId);
}
