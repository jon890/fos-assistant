package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.model.domain.type.ModelTier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelTierDefinitionRepository extends JpaRepository<ModelTierDefinition, Long> {

    List<ModelTierDefinition> findByGroupIdOrderByTier(Long groupId);

    Optional<ModelTierDefinition> findByGroupIdAndTier(Long groupId, ModelTier tier);

    void deleteByGroupId(Long groupId);
}
