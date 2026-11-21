package com.bifos.assistant.admin.secret.presentation;

import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthSigningAdminDtos.RegeneratedSigningView;
import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthSigningAdminDtos.RewriteView;
import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthSigningAdminDtos.SigningView;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthSigning;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthSigningRewrite;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.application.UserDisplayNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 「서버 비밀값」 화면에서 단추 연결의 서명 비밀을 보고 새로 만든다. 값은 서버가 만들고 사람이 넣지 않는다.
 *
 * <p>{@code ADMIN} 만 부른다. 새로 만들면 옛 통로 표식은 바로 401 이고 모든 단추 연결의 통로 주소를 다시 쓴다.
 */
@RestController
@RequestMapping("/api/v1/admin/secrets/connector-oauth/signing")
@RequiredArgsConstructor
public class ConnectorOAuthSigningAdminController {
    private final ConnectorOAuthSigning signing;
    private final CurrentUserProvider currentUser;
    private final UserDisplayNameService userNames;


    @GetMapping
    public SigningView status() {
        currentUser.requireAdmin();
        return SigningView.of(signing.status(), userNames::find);
    }


    @PostMapping("/regenerate")
    public RegeneratedSigningView regenerate() {
        Long adminId = currentUser.requireAdmin().id();
        ConnectorOAuthSigningRewrite rewrite = signing.regenerate(adminId);
        return new RegeneratedSigningView(SigningView.of(signing.status(), userNames::find), RewriteView.of(rewrite));
    }
}
