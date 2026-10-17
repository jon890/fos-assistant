package com.bifos.assistant.chat.modelchoice.infra;

import com.bifos.assistant.chat.modelchoice.domain.ModelHidden;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelHiddenRepository extends JpaRepository<ModelHidden, Long> {

    List<ModelHidden> findByGroupIdOrderByProviderAscModelAsc(Long groupId);


    void deleteByGroupId(Long groupId);
}
