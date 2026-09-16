package com.bifos.assistant.user.domain;

import com.bifos.assistant.user.infra.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the caller into a persisted user.
 *
 * <p>The web tier only mints a token for an allow-listed family address, so the first caller
 * bootstraps the family and becomes its admin. Membership alone grants nothing: a user still
 * cannot run an agent until an admin binds a Hermes profile to them.
 */
@Service
public class UserProvisioningService {

    /** Single household for the MVP. Multi-family support would replace this with a lookup. */
    public static final long DEFAULT_FAMILY_ID = 1L;

    private final AppUserRepository users;

    public UserProvisioningService(AppUserRepository users) {
        this.users = users;
    }

    @Transactional
    public AppUser resolve(String email, String displayName) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(AppUser.of(email, displayName, DEFAULT_FAMILY_ID, firstUserRole())));
    }

    private UserRole firstUserRole() {
        return users.existsByFamilyId(DEFAULT_FAMILY_ID) ? UserRole.MEMBER : UserRole.ADMIN;
    }
}
