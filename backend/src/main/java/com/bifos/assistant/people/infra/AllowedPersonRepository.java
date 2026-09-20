package com.bifos.assistant.people.infra;

import com.bifos.assistant.people.domain.AllowedPerson;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllowedPersonRepository extends JpaRepository<AllowedPerson, Long> {

    /**
     * 들어와도 되는 사람을 찾는다.
     *
     * <p>메일 주소는 {@link AllowedPerson#normalizeEmail(String)} 을 지난 값이어야 한다.
     */
    Optional<AllowedPerson> findByEmailAndEnabledTrue(String email);

    boolean existsByEmail(String email);

    boolean existsByHermesProfile(String hermesProfile);
}
