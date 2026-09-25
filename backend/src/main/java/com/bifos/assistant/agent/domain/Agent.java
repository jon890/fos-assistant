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

    /**
     * 이 에이전트를 묶어 둔 다중 에이전트 흐름의 이름. 비어 있으면 Hermes 를 한 번 부른다.
     *
     * <p>모르는 이름이 적혀 있으면 기동할 때 {@code FlowRegistry} 가 실패시킨다. 잘못 적힌 이름을
     * 사용자가 그 에이전트를 고를 때 알게 되면 늦기 때문이다.
     */
    @Column(name = "flow", length = 64)
    private String flow;

    /** 새 대화 화면에 보일 한 줄 소개. 비어 있으면 {@code null} 이다. */
    @Column(name = "tagline", length = 200)
    private String tagline;

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
    public String flow() { return flow; }
    public String tagline() { return tagline; }

    /** 흐름 이름을 붙이거나 뗀다. 빈 문자열은 비운 것과 같게 본다. */
    public void assignFlow(String flow) {
        this.flow = flow == null || flow.isBlank() ? null : flow.strip();
    }

    /** 한 줄 소개를 바꾼다. 앞뒤 공백을 떼고, 비면 {@code null} 로 둔다. */
    public void changeTagline(String tagline) {
        String stripped = tagline == null ? "" : tagline.strip();
        this.tagline = stripped.isEmpty() ? null : stripped;
    }

    /**
     * 이 에이전트의 대화에 사진을 붙일 수 있다.
     *
     * <p>흐름은 Hermes 를 한 번 부르는 경로를 거치지 않아 사진이 놓인 자리를 입력에 덧붙일 수 없다. 그래서
     * 흐름이 붙은 에이전트는 받지 않는다. 받을지 판정하는 곳은 모두 이 메서드를 부른다.
     */
    public boolean acceptsAttachments() {
        return flow == null || flow.isBlank();
    }

    public boolean isReadableBy(Long userId) {
        return visibility == AgentVisibility.FAMILY || Objects.equals(ownerUserId, userId);
    }

    public void changeAccess(boolean enabled, AgentVisibility visibility, Long ownerUserId) {
        this.enabled = enabled;
        this.visibility = visibility;
        this.ownerUserId = ownerUserId;
    }

    /**
     * Hermes API 주소를 바꾼다.
     *
     * <p>생성자와 같게 끝의 {@code /} 를 떼고 저장한다. 그러지 않으면 {@code //v1/runs} 처럼 부르게 된다.
     */
    public void changeApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl.strip());
    }

    public boolean syncModel(String model) {
        boolean changed = !Objects.equals(this.model, model);
        this.model = model;
        this.modelSyncedAt = Instant.now();
        return changed;
    }
}
