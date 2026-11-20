package com.bifos.assistant.admin.secret.presentation;

import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.UpdatedBy;
import com.bifos.assistant.connector.connection.application.ConnectorOAuthApps;
import com.bifos.assistant.connector.connection.application.dto.ConnectorOAuthAppStatus;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 「서버 비밀값」 화면에서 카탈로그가 선언한 OAuth 앱을 관리한다. */
@RestController
@RequestMapping("/api/v1/admin/secrets/connector-oauth/apps")
@RequiredArgsConstructor
public class ConnectorOAuthAppAdminController {
    private final ConnectorOAuthApps apps;
    private final CurrentUserProvider currentUser;
    private final UserDisplayNameService userNames;


    @GetMapping
    public AppsView list() {
        currentUser.requireAdmin();
        return new AppsView(apps.storeAvailable(), apps.list().stream().map(this::view).toList());
    }


    @PutMapping("/{connectorId}")
    public AppView save(@PathVariable String connectorId, @RequestBody AppRequest request) {
        Long adminId = currentUser.requireAdmin().id();
        return view(apps.save(connectorId, request.clientId(), request.clientSecret(), request.appSlug(), adminId));
    }


    @DeleteMapping("/{connectorId}")
    public ResponseEntity<Void> clear(@PathVariable String connectorId) {
        apps.clear(connectorId, currentUser.requireAdmin().id());
        return ResponseEntity.noContent().build();
    }


    private AppView view(ConnectorOAuthAppStatus app) {
        Long userId = app.updatedBy();
        UpdatedBy by = userId == null ? null : new UpdatedBy(userId, userNames.find(userId));
        return new AppView(
            app.connectorId(), app.connectorTitle(), app.provider(), app.clientId(), app.appSlug(),
            app.set(), app.prefix(), by, app.updatedAt());
    }


    public record AppRequest(String clientId, String clientSecret, String appSlug) {
        @Override
        public String toString() {
            return "AppRequest[값 생략]";
        }
    }

    public record AppsView(boolean storeAvailable, List<AppView> apps) {
    }

    public record AppView(
        String connectorId, String connectorTitle, String provider, String clientId, String appSlug,
        boolean set, String prefix, UpdatedBy updatedBy, Instant updatedAt) {
    }
}
