package com.bifos.assistant.workspace.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.workspace.domain.Workspace;
import com.bifos.assistant.workspace.domain.WorkspaceVisibility;
import com.bifos.assistant.workspace.infra.WorkspaceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registers a work area.
 *
 * <p>Areas are registered one at a time rather than discovered by scanning the mount. Discovery
 * would enable every directory it finds, and some of them hold health records, so the first
 * mistake would already be a leak. Visibility has no default for the same reason.
 */
@RestController
@RequestMapping("/api/v1/admin/workspaces")
public class WorkspaceAdminController {

    private final WorkspaceRepository workspaces;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;

    public WorkspaceAdminController(
            WorkspaceRepository workspaces, AppUserRepository users, CurrentUserProvider currentUser) {
        this.workspaces = workspaces;
        this.users = users;
        this.currentUser = currentUser;
    }

    @PostMapping
    public AdminWorkspaceView create(@Valid @RequestBody CreateWorkspaceRequest request) {
        currentUser.requireAdmin();
        if (workspaces.findByCode(request.code()).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this work area code is already used");
        }
        Long ownerUserId = null;
        if (request.visibility() == WorkspaceVisibility.PRIVATE) {
            if (request.ownerEmail() == null || request.ownerEmail().isBlank()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "a private work area needs an owner");
            }
            ownerUserId =
                    users
                            .findByEmail(request.ownerEmail())
                            .orElseThrow(
                                    () -> new ApiException(ErrorCode.VALIDATION_FAILED, "no such family member"))
                            .id();
        }
        Workspace saved =
                workspaces.save(
                        Workspace.of(
                                request.code(),
                                request.name(),
                                request.sourcePath(),
                                request.visibility(),
                                ownerUserId));
        return AdminWorkspaceView.from(saved);
    }

    @GetMapping
    public List<AdminWorkspaceView> list() {
        currentUser.requireAdmin();
        return workspaces.findAll().stream().map(AdminWorkspaceView::from).toList();
    }

    public record CreateWorkspaceRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String code,
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String sourcePath,
            @NotNull WorkspaceVisibility visibility,
            String ownerEmail) {
    }

    public record AdminWorkspaceView(
            Long id, String code, String name, String sourcePath, String visibility, Long ownerUserId,
            boolean enabled) {

        static AdminWorkspaceView from(Workspace workspace) {
            return new AdminWorkspaceView(
                    workspace.id(),
                    workspace.code(),
                    workspace.name(),
                    workspace.sourcePath(),
                    workspace.visibility().name(),
                    workspace.ownerUserId(),
                    workspace.enabled());
        }
    }
}
