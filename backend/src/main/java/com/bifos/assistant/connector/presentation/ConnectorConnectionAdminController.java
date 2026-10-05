package com.bifos.assistant.connector.presentation;

import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.presentation.ConnectionDtos.AdminConnectionView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 같은 그룹 사용자의 바인딩 목록이다. 반영 완료는 {@link AdminAgentConnectionController} 가 바인딩마다 받는다. */
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
}
