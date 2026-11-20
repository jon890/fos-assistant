package com.bifos.assistant.admin.secret.presentation;

import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthAppAdminDtos.AppRequest;
import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthAppAdminDtos.AppView;
import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthAppAdminDtos.AppsView;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthApps;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.application.UserDisplayNameService;
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
        return new AppsView(apps.storeAvailable(), apps.list().stream().map(app -> AppView.of(app, userNames::find)).toList());
    }


    @PutMapping("/{connectorId}")
    public AppView save(@PathVariable String connectorId, @RequestBody AppRequest request) {
        Long adminId = currentUser.requireAdmin().id();
        var saved = apps.save(connectorId, request.clientId(), request.clientSecret(), request.appSlug(), adminId);
        return AppView.of(saved, userNames::find);
    }


    @DeleteMapping("/{connectorId}")
    public ResponseEntity<Void> clear(@PathVariable String connectorId) {
        apps.clear(connectorId, currentUser.requireAdmin().id());
        return ResponseEntity.noContent().build();
    }
}
