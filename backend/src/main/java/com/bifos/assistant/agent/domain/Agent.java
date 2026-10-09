package com.bifos.assistant.agent.domain;

import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

@Entity
@Table(name = "agent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    @Getter
    private String code;

    @Column(nullable = false, length = 100)
    @Getter
    private String name;

    @Column(name = "hermes_profile", nullable = false, unique = true, length = 64)
    @Getter
    private String hermesProfile;

    @Column(name = "api_base_url", nullable = false, length = 255)
    @Getter
    private String apiBaseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_mode", nullable = false, length = 20)
    @Getter
    private CostMode costMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_scope", nullable = false, length = 20)
    @Getter
    private CredentialScope credentialScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Getter
    private AgentVisibility visibility;

    @Column(name = "owner_user_id")
    @Getter
    private Long ownerUserId;

    @Column(nullable = false)
    @Getter
    private boolean enabled;

    /**
     * 이 에이전트를 묶어 둔 다중 에이전트 흐름의 이름. 비어 있으면 Hermes 를 한 번 부른다.
     *
     * <p>모르는 이름이 적혀 있으면 기동할 때 {@code FlowRegistry} 가 실패시킨다. 잘못 적힌 이름을
     * 사용자가 그 에이전트를 고를 때 알게 되면 늦기 때문이다.
     */
    @Column(name = "flow", length = 64)
    @Getter
    private String flow;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 참이면 Control Plane 이 이 에이전트의 profile 을 만들었다.
     *
     * <p>지울 때 Hermes profile 까지 거두는 것은 이 값이 참일 때뿐이다. 관리자가 운영에서 만든 profile 과
     * 사용자의 기본 profile 을 가리키는 에이전트는 거짓이다(ADR-033).
     */
    @Column(name = "profile_managed", nullable = false)
    @Getter
    private boolean profileManaged;

    @Column(name = "connector_managed", nullable = false)
    @Getter
    private boolean connectorManaged;

    /**
     * 참이면 이 연결용 에이전트가 사진을 받는다. 커넥터의 선언과 도구 확인을 합친 값이다.
     *
     * <p>선언은 대시보드가 내는 manifest 에 있고 이 엔티티는 Hermes 를 부르지 못한다. 그래서 연결 흐름이 여기
     * 적는다. 선언이 참이고 연결 확인이나 관리자 반영 완료가 선언한 toolset 이 실제로 켜진 것을 확인했을 때만
     * 참이다. 등록 직후와 {@code READY} 가 아닌 연결은 늘 거짓이다(ADR-044).
     */
    @Column(name = "connector_attachments", nullable = false)
    private boolean connectorAttachments;

    /**
     * 「먼저 살펴보기에 쓰기 도구 허용」 이다. 관리자만 바꾼다(ADR-082).
     *
     * <p>참이면 이 에이전트의 먼저 살펴보기가 쓰기 toolset 과 결과물 쓰기를 쓰고 커넥터 쓰기를 승인 카드로 보낸다. 살펴보기는 시작할 때
     * 이 값을 자기 줄에 옮겨 적고 그 값으로 경계를 정한다. 그래서 도중에 바꿔도 이미 시작한 살펴보기는 바뀌지 않는다.
     */
    @Column(name = "proactive_check_writes_allowed", nullable = false)
    @Getter
    private boolean proactiveCheckWritesAllowed;

    /**
     * 지운 시각. 비어 있으면 지우지 않았다.
     *
     * <p>지운 지 {@code assistant.agents.purge-after} 가 지나면 정리 작업이 행을 지운다(ADR-20261009 / agent-purge). 대화, 실행,
     * 사용량은 행이 없어도 「지운 에이전트」 로 그린다.
     */
    @Column(name = "deleted_at")
    @Getter
    private Instant deletedAt;

    /**
     * 이 에이전트의 기본 provider 다. {@code defaultModel} 과 함께 채우거나 함께 비운다.
     *
     * <p>대화가 모델을 고르지 않았을 때 Hermes 에 명시해 보낸다(ADR-054). 비면 profile 의 값으로 돈다.
     */
    @Column(name = "default_model_provider", length = 64)
    @Getter
    private String defaultModelProvider;

    /** 이 에이전트의 기본 모델이다. */
    @Column(name = "default_model", length = 128)
    @Getter
    private String defaultModel;

    /** 이 에이전트의 기본 effort 다. 모델 없이 이 값만 둘 수 있다. */
    @Column(name = "default_reasoning_effort", length = 16)
    @Getter
    private String defaultReasoningEffort;

    /** 아직 한 번도 저장하지 않은 새 에이전트다. 데이터베이스에서 읽은 에이전트는 거짓이다. */
    @Transient
    private boolean created;

    private Agent(
            String code,
            String name,
            String hermesProfile,
            String apiBaseUrl,
            CostMode costMode,
            CredentialScope credentialScope,
            AgentVisibility visibility,
            Long ownerUserId,
            Instant now) {
        this.code = code;
        this.name = name;
        this.hermesProfile = hermesProfile;
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
        this.costMode = costMode;
        this.credentialScope = credentialScope;
        this.visibility = visibility;
        this.ownerUserId = ownerUserId;
        this.enabled = true;
        this.createdAt = now;
        this.created = true;
    }

    /**
     * 처음 저장할 때 {@link AgentCreated} 를 한 번 낸다. 저장소의 저장이 끝난 직후 Spring Data 가 읽는다.
     *
     * <p>사건을 받는 쪽이 번호를 쓰므로 저장이 끝나 번호가 정해진 뒤에 나가야 한다.
     */
    @DomainEvents
    List<AgentCreated> createdEvents() {
        return created ? List.of(new AgentCreated(this)) : List.of();
    }

    @AfterDomainEventPublication
    void clearCreatedEvents() {
        created = false;
    }

    public static Agent of(
            String code,
            String name,
            String hermesProfile,
            String apiBaseUrl,
            CostMode costMode,
            CredentialScope credentialScope,
            AgentVisibility visibility,
            Long ownerUserId,
            Instant now) {
        return new Agent(
                code, name, hermesProfile, apiBaseUrl, costMode, credentialScope, visibility, ownerUserId, now);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 기본 모델과 effort 를 바꾼다. 검증은 부르는 쪽이 끝낸 값만 받는다. 모두 null 이면 profile 의 값으로 돌아간다. */
    public void changeDefaultModel(String provider, String model, String reasoningEffort) {
        this.defaultModelProvider = provider;
        this.defaultModel = model;
        this.defaultReasoningEffort = reasoningEffort;
    }

    /** Control Plane 이 이 에이전트의 profile 을 만들었다고 적는다. 지울 때 그 profile 까지 거둔다. */
    public void markManagedProfile() {
        this.profileManaged = true;
    }

    /** connector 가 만든 에이전트는 일반 설정 화면에서 바꾸지 않는다. */
    public void markConnectorManaged() {
        this.connectorManaged = true;
    }

    /**
     * 연결용 에이전트가 사진을 받는지 적는다. 연결용 에이전트에만 뜻이 있다.
     *
     * <p>manifest 의 선언을 그대로 옮기지 않는다. 선언과 도구 확인이 모두 참일 때만 참을 넘긴다.
     */
    public void acceptConnectorAttachments(boolean accepted) {
        this.connectorAttachments = accepted;
    }

    /**
     * 지운 에이전트로 적고 끈다.
     *
     * <p>꺼 두는 것은 {@code enabled} 만 보는 곳에서도 새 대화가 시작되지 않게 하기 위해서다.
     */
    public void markDeleted(Instant now) {
        this.deletedAt = now;
        this.enabled = false;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 흐름 이름을 붙이거나 뗀다. 빈 문자열은 비운 것과 같게 본다. */
    public void assignFlow(String flow) {
        this.flow = flow == null || flow.isBlank() ? null : flow.strip();
    }

    /**
     * 이 에이전트의 대화에 사진을 붙일 수 있다.
     *
     * <p>주인이 있는 비공개 에이전트만 받는다. 공유 에이전트는 실행 요청자와 고정 첨부 mount 의 주인이 다를 수 있다.
     * 흐름은 Hermes 를 한 번 부르는 경로를 거치지 않아 사진이 놓인 자리를 입력에 덧붙일 수 없다. 그래서
     * 흐름이 붙은 에이전트는 받지 않는다. 연결용 에이전트는 그 커넥터가 사진을 받는다고 선언했고 선언한 toolset 이 켜진 것이 확인됐을 때만 받는다.
     * 받을지 판정하는 곳은 모두 이 메서드를 부른다.
     */
    public boolean acceptsAttachments() {
        return visibility == AgentVisibility.PRIVATE
                && ownerUserId != null
                && (!connectorManaged || connectorAttachments)
                && (flow == null || flow.isBlank());
    }

    /**
     * Hermes 가 셸·파일·사진 도구를 돌릴 격리 실행 공간의 주인 키다(ADR-091).
     *
     * <p>주인이 있으면 {@code u<사용자 번호>} 로 그 사용자의 에이전트들이 한 공간을 나눠 쓴다. 주인이 없는 에이전트는
     * {@code a<에이전트 번호>} 로 그 에이전트만의 공간을 쓴다. 도구 저장과 스킬 게시가 같은 값을 보내야 하므로
     * 규칙을 여기 한 곳에 둔다.
     */
    public String sandboxOwner() {
        return ownerUserId != null ? "u" + ownerUserId : "a" + id;
    }

    public boolean isReadableBy(Long userId) {
        return visibility == AgentVisibility.GROUP || Objects.equals(ownerUserId, userId);
    }

    /** 「먼저 살펴보기에 쓰기 도구 허용」 을 켜거나 끈다. 관리자 경로만 부른다. */
    public void changeProactiveCheckWritesAllowed(boolean allowed) {
        this.proactiveCheckWritesAllowed = allowed;
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
}
