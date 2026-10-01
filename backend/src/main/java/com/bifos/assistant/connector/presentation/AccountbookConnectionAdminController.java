package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.AccountbookConnectionService;
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
@RequestMapping("/api/v1/admin/connections/accountbook")
@RequiredArgsConstructor
public class AccountbookConnectionAdminController {
    private final AccountbookConnectionService connections;
    private final CurrentUserProvider currentUser;
    @GetMapping public List<AdminConnectionView> list() { CurrentUser admin = currentUser.requireAdmin(); return connections.listForAdmin(admin).stream().map(AdminConnectionView::from).toList(); }
    @PostMapping("/{userId}/confirm") public AdminConnectionView confirm(@PathVariable Long userId) {
        var snapshot = connections.confirmApplied(currentUser.requireAdmin(), userId);
        return new AdminConnectionView(userId, null, snapshot.status().name(), snapshot.agentCode(), snapshot.restartRequired());
    }
}
