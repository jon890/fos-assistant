package com.bifos.assistant.user.domain;

import com.bifos.assistant.shared.domain.type.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.experimental.Accessors;

@Entity
@Table(name = "app_user")
@Accessors(fluent = true)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    @Getter
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    @Getter
    private String displayName;

    @Column(name = "group_id", nullable = false)
    @Getter
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Getter
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "model_default_tier", length = 16)
    @Getter
    @Accessors(fluent = true)
    private String modelDefaultTier;

    protected AppUser() {}

    private AppUser(String email, String displayName, Long groupId, UserRole role, Instant now) {
        this.email = email;
        this.displayName = displayName;
        this.groupId = groupId;
        this.role = role;
        this.createdAt = now;
    }

    public static AppUser of(String email, String displayName, Long groupId, UserRole role, Instant now) {
        return new AppUser(email, displayName, groupId, role, now);
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }
}
