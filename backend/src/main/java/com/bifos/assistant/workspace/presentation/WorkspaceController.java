package com.bifos.assistant.workspace.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.workspace.application.WorkspaceService;
import com.bifos.assistant.workspace.domain.Workspace;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;
    private final CurrentUserProvider currentUser;

    public WorkspaceController(WorkspaceService workspaces, CurrentUserProvider currentUser) {
        this.workspaces = workspaces;
        this.currentUser = currentUser;
    }

    /** Only the areas this caller may enter. A private area of another member never appears. */
    @GetMapping
    public List<WorkspaceView> readable() {
        return workspaces.readableBy(currentUser.require()).stream().map(WorkspaceView::from).toList();
    }

    public record WorkspaceView(String code, String name, String visibility) {

        static WorkspaceView from(Workspace workspace) {
            return new WorkspaceView(workspace.code(), workspace.name(), workspace.visibility().name());
        }
    }
}
