package com.bifos.assistant.admin.connector.presentation;

import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.admin.connector.presentation.ConnectionAdminDtos.AdminConnectionView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.connector.connection.application.ConnectorBindingAlerts;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.time.Instant;
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
    /** 「다시 확인」 단추의 24시간 기준은 관리자 알림과 같다. */
    private final ConnectorBindingAlerts alerts;


    @GetMapping
    public List<AdminConnectionView> list() {
        CurrentUser admin = currentUser.requireAdmin();
        Instant staleBefore = alerts.staleBefore();
        return connections.listForAdmin(admin).stream()
            .map(value -> AdminConnectionView.from(value, staleBefore))
            .toList();
    }
}
