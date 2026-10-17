package com.bifos.assistant.proactive.autonomy.infra;

import com.bifos.assistant.proactive.autonomy.domain.AutonomyPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutonomyPreferenceRepository extends JpaRepository<AutonomyPreference, Long> {
}
