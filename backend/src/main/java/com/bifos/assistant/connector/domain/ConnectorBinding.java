package com.bifos.assistant.connector.domain;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 에이전트 하나에 연결 하나를 붙인 것이다(ADR-083). 에이전트와 연결은 다대다다.
 *
 * <p>연결 상태는 「값이 확인됐는가」 이고 이 상태는 「그 에이전트의 profile 에 설치되고 반영됐는가」 다. 재시작 대기는
 * profile 마다라 여기에 둔다. 떼면 행을 지운다.
 *
 * <p>상태를 바꾸는 메서드는 시각을 인자로 받는다. 비밀 칸의 값을 받는 메서드는 두지 않는다.
 *
 * <p>옛 커넥터 에이전트({@code Agent.connectorManaged()})의 바인딩만 에이전트를 켜고 끈다. 그 에이전트는 이 바인딩 하나로
 * 돌기 때문이다. 다른 에이전트의 바인딩은 에이전트를 건드리지 않는다. 그 바인딩의 도구는 판정이 막는다.
 */
@Entity
@Table(
        name = "agent_connector_binding",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_agent_connector_binding",
                        columnNames = {"agent_id", "connection_id"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    private ConnectorConnection connection;

    /**
     * 붙일 때 manifest 가 선언한 MCP 서버 이름이다. 비밀이 아니다.
     *
     * <p>turn 마다 카탈로그를 읽지 않고, 카탈로그에서 커넥터가 빠져도 그 profile 에 설치된 서버 이름을 잃지 않게 둔다.
     * 마이그레이션이 만든 옛 바인딩은 비어 있고 연결 확인과 관리자 반영 완료가 채운다.
     */
    @Column(name = "mcp_server", length = 64)
    private String mcpServer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BindingStatus status;

    @Column(name = "restart_required", nullable = false)
    private boolean restartRequired;

    /**
     * 재시작이 필요해진 가장 늦은 설치 시각이다.
     *
     * <p>관리자가 재시작한 뒤에 다시 설치가 있었으면 반영 완료가 대기를 풀지 않게 하려고 둔다.
     */
    @Column(name = "restart_required_since")
    private Instant restartRequiredSince;

    /**
     * 반영 예정 시각이다(ADR-20261007 / connector-live-reload).
     *
     * <p>재시작 없이 공유 gateway 의 MCP 설정 맞추기 주기가 반영할 설치를 보냈을 때 적는다. 이 시각이 지나면 Control Plane 이
     * 반영 맞추기를 스스로 한 번 돌린다. 예정이 없으면 비어 있다.
     */
    @Column(name = "apply_due_at")
    private Instant applyDueAt;

    @Column(name = "desired_enabled", nullable = false)
    private boolean desiredEnabled;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private ConnectorBinding(Agent agent, ConnectorConnection connection, String mcpServer, Instant now) {
        this.agent = agent;
        this.connection = connection;
        this.mcpServer = mcpServer;
        this.status = BindingStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ConnectorBinding pending(Agent agent, ConnectorConnection connection, String mcpServer, Instant now) {
        return new ConnectorBinding(agent, connection, mcpServer, now);
    }

    /** 설치에 쓴 서버 이름을 적는다. 옛 바인딩은 확인할 때 여기로 채운다. */
    public void recordServer(String mcpServer) {
        this.mcpServer = mcpServer;
    }

    /** 설치를 보내기 전이다. 설치가 끝날 때까지 켜려는 의도를 내린다. */
    public void beginInstall(Instant now) {
        this.desiredEnabled = false;
        pending(now);
    }

    /**
     * 설치가 성공했다. 켜려는 의도가 돌아오지만 반영 확인 전이라 상태는 {@code PENDING} 이다.
     *
     * <p>재시작 대기는 앞선 대기를 지우지 않고 논리 OR 로 누적한다. 재시작이 필요한 설치였으면 그 시각을 대기의 시작으로 적는다.
     */
    public void installed(boolean restartRequired, Instant now) {
        this.desiredEnabled = true;
        this.restartRequired = this.restartRequired || restartRequired;
        if (restartRequired) {
            this.restartRequiredSince = now;
        }
        this.checkedAt = now;
        pending(now);
    }

    /** 반영을 확인했다. 재시작 대기와 반영 예정을 함께 푼다. */
    public void ready(Instant now) {
        this.status = BindingStatus.READY;
        this.restartRequired = false;
        this.applyDueAt = null;
        this.checkedAt = now;
        this.updatedAt = now;
        if (agent.connectorManaged()) {
            agent.changeAccess(true, agent.visibility(), agent.ownerUserId());
        }
    }

    public void pending(Instant now) {
        this.status = BindingStatus.PENDING;
        this.updatedAt = now;
        if (agent.connectorManaged()) {
            disableConnectorAgent();
        }
    }

    /** 반영 예정 시각을 적는다. 이미 더 늦은 예정이 있으면 그 값을 둔다. 앞선 설치가 아직 반영되지 않았을 수 있기 때문이다. */
    public void scheduleApply(Instant dueAt) {
        if (this.applyDueAt == null || this.applyDueAt.isBefore(dueAt)) {
            this.applyDueAt = dueAt;
        }
    }

    /** 예정한 확인을 시작하기 전에 비운다. 확인은 한 번만 시도한다. */
    public void clearApplyDue() {
        this.applyDueAt = null;
    }

    /** 앞선 대기 값을 지우지 않고 논리 OR 로 누적한다. */
    public void markRestartRequired(boolean restartRequired) {
        this.restartRequired = this.restartRequired || restartRequired;
    }

    /**
     * 옛 커넥터 에이전트를 끄고 사진도 받지 않는 것으로 둔다.
     *
     * <p>{@code READY} 가 아닌 바인딩의 옛 에이전트에 사진 받기가 참으로 남지 않게 한다. 참으로 두는 곳은 연결 확인과
     * 관리자 반영 완료가 선언한 toolset 을 확인한 자리다.
     */
    private void disableConnectorAgent() {
        agent.changeAccess(false, agent.visibility(), agent.ownerUserId());
        agent.acceptConnectorAttachments(false);
    }
}
