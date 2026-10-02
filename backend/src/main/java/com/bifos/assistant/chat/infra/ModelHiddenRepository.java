package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ModelHidden;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelHiddenRepository extends JpaRepository<ModelHidden, Long> {

    List<ModelHidden> findByGroupIdOrderByProviderAscModelAsc(Long groupId);

    void deleteByGroupId(Long groupId);
}
