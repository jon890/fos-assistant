package com.bifos.assistant.workspace.infra;

import com.bifos.assistant.workspace.domain.Workspace;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    Optional<Workspace> findByCode(String code);

    List<Workspace> findByEnabledTrueOrderByCodeAsc();
}
