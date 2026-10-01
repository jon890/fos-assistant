package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.model.ConnectionSnapshot;
import com.bifos.assistant.connector.presentation.ConnectionDtos.AdminConnectionView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/connections")
@RequiredArgsConstructor
public class ConnectorConnectionAdminController {
    private final ConnectorConnectionService connections;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<AdminConnectionView> list() {
        CurrentUser admin = currentUser.requireAdmin();
        return connections.listForAdmin(admin).stream()
                .map(AdminConnectionView::from)
                .toList();
    }

    /** 응답에는 대상 사용자의 칸 값과 비밀 앞부분을 담지 않는다. */
    @PostMapping("/{id}/{userId}/confirm")
    public AdminConnectionView confirm(@PathVariable String id, @PathVariable Long userId) {
        ConnectionSnapshot snapshot = connections.confirmApplied(currentUser.requireAdmin(), id, userId);
        return new AdminConnectionView(
                id,
                userId,
                null,
                snapshot.status().name(),
                snapshot.agentCode(),
                snapshot.restartRequired(),
                snapshot.undeclaredTools());
    }
}
