package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.AutonomyPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutonomyPreferenceRepository extends JpaRepository<AutonomyPreference, Long> {}
