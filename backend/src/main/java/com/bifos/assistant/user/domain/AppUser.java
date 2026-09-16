package com.bifos.assistant.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    private AppUser(String email, String displayName, Long familyId, UserRole role) {
        this.email = email;
        this.displayName = displayName;
        this.familyId = familyId;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public static AppUser of(String email, String displayName, Long familyId, UserRole role) {
        return new AppUser(email, displayName, familyId, role);
    }

    public Long id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public Long familyId() {
        return familyId;
    }

    public UserRole role() {
        return role;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }
}
