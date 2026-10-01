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
 * 커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다(ADR-048, ADR-049).
 *
 * <p>칸의 뜻은 {@code docs/data-schema.md} 의 「connector_action」 이 갖는다. 허용과 거절도 한 줄씩 남긴다. 사용자와
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
}
