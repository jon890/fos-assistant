package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.usage.domain.AgentExecution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다(ADR-049, ADR-050).
 *
 * <p>칸의 뜻은 {@code docs/backend/schema/connector.md} 의 「connector_action」 이 갖는다. 허용과 거절도 한 줄씩 남긴다. 사용자와
 * 에이전트는 번호로만 둔다. 실행과 대화는 지워져도 이 줄을 남기므로 외래 키를 걸지 않는다.
 */
@Entity
@Table(
        name = "connector_action",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_connector_action_dedupe_key", columnNames = "dedupe_key"),
            @UniqueConstraint(name = "uk_connector_action_public_id", columnNames = "public_id")
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorAction {

    /** 승인은 받았으나 연결이 준비되지 않았거나 정책이 바뀌어 실행하지 않고 끝낸 줄의 {@code errorCode} 다. */
    public static final String NOT_EXECUTABLE = "not_executable";

    /** 연결을 해제하거나 값을 다시 등록해 끝낸 줄의 {@code errorCode} 다. */
    public static final String CONNECTION_CHANGED = "connection_changed";

    /** 상시 허락을 닫은 도구의 인자에 화면에서 가려지는 글이 있어 실행하지 않고 끝낸 줄의 {@code errorCode} 다(ADR-065). */
    public static final String HIDDEN_ARGS = "hidden_args";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 화면과 모델에 보이는 승인 요청 번호다. 대화의 공개 식별자와 같은 방식이다(ADR-025).
     *
     * <p>넣을 때 Hibernate 가 v7 을 채운다. {@code BINARY} 로 못 박아 테스트의 H2 와 운영의 MySQL 이 같은 바이트
     * 16개로 저장한다.
     */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "connector_id", nullable = false, length = 64)
    private String connectorId;

    /** MCP 서버의 원래 도구 이름이다. 등록 이름과 맞는 원래 이름을 확인하지 못한 호출은 비운다. */
    @Column(name = "tool_name", length = 128)
    private String toolName;

    /** hook 이 받은 등록 이름이다. */
    @Column(name = "hermes_tool", nullable = false, length = 128)
    private String hermesTool;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk", length = 16)
    private ToolRisk risk;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", length = 16)
    private ToolApproval approvalMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20)
    private ActionDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "deny_reason", length = 40)
    private ActionDenyReason denyReason;

    /** hook 에 통과로 답했는가. */
    @Column(name = "passed", nullable = false)
    private boolean passed;

    /** 승인 줄만 채운다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private ActionStatus status;

    @Column(name = "origin_execution_id", nullable = false)
    private Long originExecutionId;

    /** 그 실행의 대화다. 대화 없이 돈 실행이면 비운다. */
    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "dedupe_key", nullable = false, length = 64)
    private String dedupeKey;

    /** 승인 줄만 채운다. hook 이 보낸 글자 그대로다. */
    @Column(name = "args_json", columnDefinition = "MEDIUMTEXT")
    private String argsJson;

    /** 인자 글의 SHA-256 이다. 원문을 두지 않는 줄에서도 무엇을 불렀는지 맞춰 볼 수 있다. */
    @Column(name = "args_sha256", nullable = false, length = 64)
    private String argsSha256;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "result_text", columnDefinition = "MEDIUMTEXT")
    private String resultText;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "result_delivered_at")
    private Instant resultDeliveredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 판정 한 줄이다. 인자 원문과 승인 상태는 담지 않는다.
     *
     * @param toolName 등록 이름과 맞는 것을 확인한 원래 도구 이름. 확인하지 못했으면 null
     * @param passed hook 에 통과로 답했는가
     */
    public static ConnectorAction decided(
            ConnectorConnection connection,
            AgentExecution origin,
            String hermesTool,
            String toolName,
            ToolPolicyDecision decision,
            boolean passed,
            String dedupeKey,
            String argsSha256,
            Instant now) {
        ConnectorAction action = new ConnectorAction();
        action.userId = connection.userId();
        action.agentId = connection.agent().id();
        action.connectorId = connection.connectorId();
        action.toolName = toolName;
        action.hermesTool = hermesTool;
        action.risk = decision.risk();
        action.approvalMode = decision.approval();
        action.decision = decision.decision();
        action.denyReason = decision.denyReason();
        action.passed = passed;
        action.originExecutionId = origin.id();
        action.conversationId = origin.conversationId();
        action.dedupeKey = dedupeKey;
        action.argsSha256 = argsSha256;
        action.createdAt = now;
        return action;
    }

    /**
     * 판정 줄을 승인 줄로 만든다. 저장하기 전에만 부른다.
     *
     * @param argsJson hook 이 보낸 인자 글 그대로. 승인하면 이 값으로 실행한다
     */
    public void awaitApproval(String argsJson, Instant expiresAt) {
        if (decision != ActionDecision.NEEDS_APPROVAL || passed || status != null) {
            throw new IllegalStateException("only a blocked NEEDS_APPROVAL action can await approval");
        }
        this.status = ActionStatus.PENDING;
        this.argsJson = argsJson;
        this.expiresAt = expiresAt;
    }

    /** 승인했다. 이 전이를 커밋한 뒤에만 실행을 보낸다. */
    public void beginExecution(Instant now) {
        require(ActionStatus.PENDING);
        this.status = ActionStatus.EXECUTING;
        this.decidedAt = now;
    }

    public void succeed(String resultText, Instant now) {
        require(ActionStatus.EXECUTING);
        this.status = ActionStatus.SUCCEEDED;
        this.resultText = resultText;
        this.executedAt = now;
    }

    /** @param errorCode 공통 오류 어휘의 글자 */
    public void fail(String errorCode, String resultText, Instant now) {
        require(ActionStatus.EXECUTING);
        this.status = ActionStatus.FAILED;
        this.errorCode = errorCode;
        this.resultText = resultText;
        this.executedAt = now;
    }

    /** 실행을 보냈으나 결과를 모른다. 다시 실행하지 않는다. */
    public void unknown(Instant now) {
        require(ActionStatus.EXECUTING);
        this.status = ActionStatus.UNKNOWN;
        this.executedAt = now;
    }

    public void reject(Instant now) {
        require(ActionStatus.PENDING);
        this.status = ActionStatus.REJECTED;
        this.decidedAt = now;
    }

    /**
     * 사용자가 거절한 것이 아니라 시스템이 실행하지 않고 끝냈다. 상태는 거절과 같고 까닭을 {@code errorCode} 에 남긴다.
     *
     * @param reason {@link #NOT_EXECUTABLE}, {@link #CONNECTION_CHANGED}, {@link #HIDDEN_ARGS} 가운데 하나
     */
    public void refuse(String reason, Instant now) {
        reject(now);
        this.errorCode = reason;
    }

    public void expire(Instant now) {
        require(ActionStatus.PENDING);
        this.status = ActionStatus.EXPIRED;
        this.decidedAt = now;
    }

    /** 결과나 거절, 만료를 대화에 전했다. */
    public void markDelivered(Instant now) {
        this.resultDeliveredAt = now;
    }

    /** 이 도구에 상시 허락을 줄 수 있는가. 늘 승인을 받는 도구와 원래 이름을 모르는 호출에는 주지 못한다. */
    public boolean grantAllowed() {
        return approvalMode == ToolApproval.REQUIRED && toolName != null;
    }

    private void require(ActionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("connector action must be " + expected + " but is " + status);
        }
    }
}
