package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.ProviderState;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderStateRepository extends JpaRepository<ProviderState, String> {

    List<ProviderState> findByBlockedUntilAfterOrderByProviderAsc(Instant now);
}
