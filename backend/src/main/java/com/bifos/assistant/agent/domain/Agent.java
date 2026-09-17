package com.bifos.assistant.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "agent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "hermes_profile", nullable = false, unique = true, length = 64)
    private String hermesProfile;

    @Column(name = "api_base_url", nullable = false, length = 255)
    private String apiBaseUrl;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "model_synced_at")
    private Instant modelSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_mode", nullable = false, length = 20)
    private CostMode costMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_scope", nullable = false, length = 20)
    private CredentialScope credentialScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentVisibility visibility;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private Agent(String code, String name, String hermesProfile, String apiBaseUrl, String provider,
            String model, CostMode costMode, CredentialScope credentialScope,
            AgentVisibility visibility, Long ownerUserId) {
        this.code = code;
        this.name = name;
        this.hermesProfile = hermesProfile;
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
        this.provider = provider;
        this.model = model;
        this.costMode = costMode;
        this.credentialScope = credentialScope;
        this.visibility = visibility;
        this.ownerUserId = ownerUserId;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public static Agent of(String code, String name, String hermesProfile, String apiBaseUrl,
            String provider, String model, CostMode costMode, CredentialScope credentialScope,
            AgentVisibility visibility, Long ownerUserId) {
        return new Agent(code, name, hermesProfile, apiBaseUrl, provider, model, costMode,
                credentialScope, visibility, ownerUserId);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public Long id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String hermesProfile() { return hermesProfile; }
    public String apiBaseUrl() { return apiBaseUrl; }
    public String provider() { return provider; }
    public String model() { return model; }
    public Instant modelSyncedAt() { return modelSyncedAt; }
    public CostMode costMode() { return costMode; }
    public CredentialScope credentialScope() { return credentialScope; }
    public AgentVisibility visibility() { return visibility; }
    public Long ownerUserId() { return ownerUserId; }
    public boolean enabled() { return enabled; }

    public boolean isReadableBy(Long userId) {
        return visibility == AgentVisibility.FAMILY || Objects.equals(ownerUserId, userId);
    }

    public void changeAccess(boolean enabled, AgentVisibility visibility, Long ownerUserId) {
        this.enabled = enabled;
        this.visibility = visibility;
        this.ownerUserId = ownerUserId;
    }

    public boolean syncModel(String model) {
        boolean changed = !Objects.equals(this.model, model);
        this.model = model;
        this.modelSyncedAt = Instant.now();
        return changed;
    }
}
