package com.bifos.assistant.credential.infra;

import com.bifos.assistant.credential.domain.HermesProfileBinding;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HermesProfileBindingRepository extends JpaRepository<HermesProfileBinding, Long> {

    Optional<HermesProfileBinding> findByUserId(Long userId);

    Optional<HermesProfileBinding> findByProfileName(String profileName);
}
