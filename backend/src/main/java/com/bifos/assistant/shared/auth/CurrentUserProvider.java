package com.bifos.assistant.shared.auth;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first");
        }
        return user;
    }

    public CurrentUser requireAdmin() {
        CurrentUser user = require();
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the family admin");
        }
        return user;
    }
}
