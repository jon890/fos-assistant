package com.bifos.assistant.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Binds one user to exactly one Hermes profile.
 *
 * <p>The profile owns the AI credential: Hermes resolves {@code ${VAR}} in a profile's config
 * against that profile's own {@code .env}, so one member's key is never visible to another. This
 * row therefore stores the profile name and its routing metadata, never a secret. The API key that
 * fronts the profile lives in host configuration keyed by profile name.
 */
@Entity
@Table(name = "hermes_profile_binding")
public class HermesProfileBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "profile_name", nullable = false, unique = true, length = 64)
    private String profileName;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_mode", nullable = false, length = 20)
    private CostMode costMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BindingStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HermesProfileBinding() {
    }

    private HermesProfileBinding(
            Long userId, String profileName, String provider, String model, CostMode costMode) {
        this.userId = userId;
        this.profileName = profileName;
        this.provider = provider;
        this.model = model;
        this.costMode = costMode;
        this.status = BindingStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public static HermesProfileBinding of(
            Long userId, String profileName, String provider, String model, CostMode costMode) {
        return new HermesProfileBinding(userId, profileName, provider, model, costMode);
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public String profileName() {
        return profileName;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public CostMode costMode() {
        return costMode;
    }

    public BindingStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == BindingStatus.ACTIVE;
    }

    public void disable() {
        this.status = BindingStatus.DISABLED;
    }
}
