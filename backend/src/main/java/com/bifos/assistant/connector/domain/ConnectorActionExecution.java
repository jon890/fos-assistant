package com.bifos.assistant.connector.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** 승인 줄에 속한 불변 금융 실행 내용이다. 본문은 contentKeyId가 있으면 암호문이다. */
@Entity
@Table(
        name = "connector_action_execution",
        indexes = @Index(name = "idx_connector_action_execution_request", columnList = "request_key"),
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_connector_action_execution_ticket", columnNames = "ticket_id"),
            @UniqueConstraint(
                    name = "uk_connector_action_execution_supersedes",
                    columnNames = "supersedes_unknown_action_id")
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorActionExecution implements Persistable<Long> {
    @Transient
    private boolean persisted;

    @Id
    @Column(name = "action_id")
    private Long actionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ConnectorAction action;

    @Column(name = "connection_id", nullable = false, updatable = false)
    private Long connectionId;

    @Column(name = "binding_id", nullable = false, updatable = false)
    private Long bindingId;

    @Column(name = "connection_updated_at", nullable = false, updatable = false)
    private Instant connectionUpdatedAt;

    @Column(name = "binding_updated_at", nullable = false, updatable = false)
    private Instant bindingUpdatedAt;

    @Column(name = "execution_args_json", nullable = false, updatable = false, columnDefinition = "MEDIUMTEXT")
    private String executionArgsJson;

    @Column(name = "summary_json", nullable = false, updatable = false, columnDefinition = "MEDIUMTEXT")
    private String summaryJson;

    @Column(name = "scope_json", nullable = false, updatable = false, columnDefinition = "MEDIUMTEXT")
    private String scopeJson;

    @Column(name = "content_key_id", updatable = false)
    private Long contentKeyId;

    @Column(name = "execution_args_sha256", nullable = false, updatable = false, length = 64)
    private String executionArgsSha256;

    @Column(name = "scope_sha256", nullable = false, updatable = false, length = 64)
    private String scopeSha256;

    @Column(name = "request_key", nullable = false, updatable = false, length = 64)
    private String requestKey;

    /** 명시적 새 요청 경로를 열기 전에는 NULL로 두는 이력 식별자다. */
    @Column(name = "supersedes_unknown_action_id", updatable = false)
    private Long supersedesUnknownActionId;

    @Column(name = "protocol", nullable = false, updatable = false, length = 32)
    private String protocol;

    /** 권한 원문을 담지 않는다. 발급과 소비 경로는 별도 구현이 맡는다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "ticket_id", columnDefinition = "BINARY(16)")
    private UUID ticketId;

    @Column(name = "ticket_expires_at")
    private Instant ticketExpiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 검증하고 암호화한 값을 담는다. 원래 action의 인자와 상태를 바꾸지 않는다. */
    public static ConnectorActionExecution stored(
            ConnectorAction action,
            Long connectionId,
            Long bindingId,
            Instant connectionUpdatedAt,
            Instant bindingUpdatedAt,
            String executionArgsJson,
            String summaryJson,
            String scopeJson,
            Long contentKeyId,
            String executionArgsSha256,
            String scopeSha256,
            String requestKey,
            String protocol,
            Instant now) {
        ConnectorActionExecution row = new ConnectorActionExecution();
        row.action = Objects.requireNonNull(action);
        row.actionId = Objects.requireNonNull(action.id());
        row.connectionId = Objects.requireNonNull(connectionId);
        row.bindingId = Objects.requireNonNull(bindingId);
        row.connectionUpdatedAt = Objects.requireNonNull(connectionUpdatedAt);
        row.bindingUpdatedAt = Objects.requireNonNull(bindingUpdatedAt);
        row.executionArgsJson = Objects.requireNonNull(executionArgsJson);
        row.summaryJson = Objects.requireNonNull(summaryJson);
        row.scopeJson = Objects.requireNonNull(scopeJson);
        row.contentKeyId = contentKeyId;
        row.executionArgsSha256 = Objects.requireNonNull(executionArgsSha256);
        row.scopeSha256 = Objects.requireNonNull(scopeSha256);
        row.requestKey = Objects.requireNonNull(requestKey);
        row.protocol = Objects.requireNonNull(protocol);
        row.createdAt = Objects.requireNonNull(now);
        return row;
    }

    /** 기존 승인 번호를 기본 키로 쓰므로 새 내용은 persist 경로로 저장한다. */
    @Override
    public Long getId() {
        return actionId();
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
