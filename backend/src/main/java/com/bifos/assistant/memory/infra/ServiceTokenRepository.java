package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.ServiceToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTokenRepository extends JpaRepository<ServiceToken, Long> {

    Optional<ServiceToken> findByTokenHash(String tokenHash);

    List<ServiceToken> findByUserIdOrderByIdDesc(Long userId);

    List<ServiceToken> findByUserIdAndRevokedAtIsNull(Long userId);
}
