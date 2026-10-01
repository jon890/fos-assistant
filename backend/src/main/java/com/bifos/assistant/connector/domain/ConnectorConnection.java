package com.bifos.assistant.connector.domain;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 사용자 한 사람과 커넥터 하나의 연결이다(ADR-043).
 *
 * <p>상태를 바꾸는 메서드는 시각을 인자로 받는다. 비밀 칸의 원문을 받는 메서드는 두지 않는다.
 */
@Entity
@Table(
        name = "connector_connection",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_connector_connection_user_connector",
                        columnNames = {"user_id", "connector_id"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorConnection {
    /** {@code fields} 열의 크기다. 저장할 JSON 텍스트가 이보다 길면 등록을 받지 않는다. */
    public static final int FIELDS_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "connector_id", nullable = false, length = 64)
    private String connectorId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false, unique = true)
    private Agent agent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;

    /** JSON 텍스트 열이다. {@code ConnectionFieldsConverter} 가 자동으로 읽고 쓴다. */
    @Column(nullable = false, length = FIELDS_LENGTH)
    private ConnectionFields fields;

    @Column(name = "restart_required", nullable = false)
    private boolean restartRequired;

    @Column(name = "desired_enabled", nullable = false)
    private boolean desiredEnabled;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private ConnectorConnection(Long userId, String connectorId, Agent agent, Instant now) {
        this.userId = userId;
        this.connectorId = connectorId;
        this.agent = agent;
        this.status = ConnectionStatus.PENDING;
        this.fields = ConnectionFields.empty();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ConnectorConnection pending(Long userId, String connectorId, Agent agent, Instant now) {
        return new ConnectorConnection(userId, connectorId, agent, now);
    }

    /** 외부 반영이 모두 성공했다. 활성화 후보가 되지만 실행 확인 전이라 상태는 {@code PENDING} 이다. */
    public void registered(ConnectionFields fields, boolean restartRequired, Instant now) {
        this.fields = fields;
        this.restartRequired = this.restartRequired || restartRequired;
        this.status = ConnectionStatus.PENDING;
        this.checkedAt = now;
        this.updatedAt = now;
        this.desiredEnabled = true;
        disableAgent();
    }

    public void ready(Instant now) {
        this.status = ConnectionStatus.READY;
        this.restartRequired = false;
        this.checkedAt = now;
        this.updatedAt = now;
        agent.changeAccess(true, agent.visibility(), agent.ownerUserId());
    }

    public void pending(Instant now) {
        this.status = ConnectionStatus.PENDING;
        this.updatedAt = now;
        disableAgent();
    }

    public void beginRegister(Instant now) {
        this.desiredEnabled = false;
        pending(now);
    }

    /** 앞선 대기 값을 지우지 않고 논리 OR 로 누적한다. */
    public void markRestartRequired(boolean restartRequired) {
        this.restartRequired = this.restartRequired || restartRequired;
    }

    public void beginDisconnect(Instant now) {
        this.desiredEnabled = false;
        pending(now);
    }

    public void confirmDisconnected(Instant now) {
        this.restartRequired = false;
        disconnected(false, now);
    }

    /** 행은 이력을 위해 남기고 칸 값과 비밀 앞부분을 비운다. */
    public void disconnected(boolean restartRequired, Instant now) {
        this.status = ConnectionStatus.DISCONNECTED;
        this.desiredEnabled = false;
        this.fields = ConnectionFields.empty();
        this.restartRequired = this.restartRequired || restartRequired;
        this.checkedAt = now;
        this.updatedAt = now;
        disableAgent();
    }

    private void disableAgent() {
        agent.changeAccess(false, agent.visibility(), agent.ownerUserId());
    }
}
