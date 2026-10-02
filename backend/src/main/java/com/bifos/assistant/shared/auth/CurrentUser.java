package com.bifos.assistant.shared.auth;

import com.bifos.assistant.user.domain.type.UserRole;

public record CurrentUser(Long id, String email, String displayName, Long groupId, UserRole role) {
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
