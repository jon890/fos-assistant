package com.bifos.assistant.shared.auth;

import com.bifos.assistant.user.domain.UserRole;

public record CurrentUser(Long id, String email, String displayName, UserRole role) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
