package com.bifos.assistant.admin.secret.presentation;

import com.bifos.assistant.admin.secret.application.ServerSecretAdminService;
import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.OverviewView;
import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.RegenerateVapidRequest;
import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.RegeneratedView;
import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.ReinstallView;
import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminDtos.VapidChangedView;
import com.bifos.assistant.connector.connection.application.dto.BrowserBindingReinstall;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.application.UserDisplayNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자가 서버 비밀값의 상태를 보고 브라우저 중계 서명 비밀을 새로 만들고 지우는 경로다(서버 비밀값 스펙 REQ1-REQ3, REQ18).
 *
 * <p>{@code ADMIN} 만 부른다. 응답에 원문은 없다. 값을 받는 범용 쓰기 경로를 두지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/secrets")
@RequiredArgsConstructor
public class ServerSecretAdminController {

    private final ServerSecretAdminService secrets;
    private final UserDisplayNameService userNames;
    private final CurrentUserProvider currentUser;


    @GetMapping
    public OverviewView overview() {
        currentUser.requireAdmin();
        return OverviewView.of(secrets.overview(), userNames::find);
    }


    @PostMapping("/browser-gateway/regenerate")
    public RegeneratedView regenerateGateway() {
        CurrentUser admin = currentUser.requireAdmin();
        BrowserBindingReinstall result = secrets.regenerateGateway(admin.id());
        OverviewView view = OverviewView.of(secrets.overview(), userNames::find);
        return new RegeneratedView(view.storeAvailable(), view.browserGateway(), ReinstallView.of(result));
    }


    @PostMapping("/vapid/regenerate")
    public VapidChangedView regenerateVapid(@RequestBody(required = false) RegenerateVapidRequest request) {
        CurrentUser admin = currentUser.requireAdmin();
        int released = secrets.regenerateVapid(admin.id(), admin.email(), request == null ? null : request.subject(),
            request == null ? null : request.expectedPublicKey());
        OverviewView view = OverviewView.of(secrets.overview(), userNames::find);
        return new VapidChangedView(view.storeAvailable(), view.vapid(), released);
    }


    @DeleteMapping("/vapid")
    public VapidChangedView deleteVapid() {
        CurrentUser admin = currentUser.requireAdmin();
        int released = secrets.deleteVapid(admin.id());
        OverviewView view = OverviewView.of(secrets.overview(), userNames::find);
        return new VapidChangedView(view.storeAvailable(), view.vapid(), released);
    }


    @DeleteMapping("/browser-gateway")
    public ResponseEntity<Void> deleteGateway() {
        CurrentUser admin = currentUser.requireAdmin();
        secrets.deleteGateway(admin.id());
        return ResponseEntity.noContent().build();
    }
}
