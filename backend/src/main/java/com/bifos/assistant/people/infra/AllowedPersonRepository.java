package com.bifos.assistant.people.infra;

import com.bifos.assistant.people.domain.AllowedPerson;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsByHermesProfile(String hermesProfile);

    @Modifying
    @Query("""
            update AllowedPerson person set person.lastLoginAt = :at
            where person.email = :email and person.enabled = true
                and (person.lastLoginAt is null or person.lastLoginAt < :at)
            """)
    int updateLastLoginAtIfNewer(@Param("email") String email, @Param("at") Instant at);
}
