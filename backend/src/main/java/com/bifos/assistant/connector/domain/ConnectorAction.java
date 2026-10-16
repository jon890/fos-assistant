package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.domain.AgentExecution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다(ADR-049, ADR-050).
 *
 * <p>칸의 뜻은 {@code backend/docs/data-schema.md} 의 「connector_action」 이 갖는다. 허용과 거절도 한 줄씩 남긴다. 사용자와
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

    public static final String UNREADABLE_ARGS = "읽을 수 없는 인자입니다.";
    public static final String UNREADABLE_RESULT = "읽을 수 없는 결과입니다.";
    public static final String BODY_ERASED = "본문 삭제됨";

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

    /**
     * 판정한 실행의 에이전트다. 승인한 호출은 이 에이전트의 profile 에서 실행한다. 옛 줄은 옛 커넥터 에이전트를 가리킨다.
     *
     * <p>지운 에이전트를 정리하면 비어 있다(ADR-20261009 / agent-purge).
     */
    @Column(name = "agent_id")
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

    /** 먼저 살펴보기 트리의 실행에서 나온 줄인가. 그 트리의 쓰기는 허락과 상관없이 늘 승인을 받아 대화 허락을 주지 않는다. */
    @ColumnDefault("false")
    @Column(name = "check_tree", nullable = false)
    private boolean checkTree;

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
    private String storedArgsJson;

    @Column(name = "args_key_id")
    private Long argsKeyId;

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
    private String storedResultText;

    @Column(name = "result_key_id")
    private Long resultKeyId;

    @Transient
    @Getter(AccessLevel.NONE)
    private ConnectorActionBodyOpener opener;

    @Transient
    @Getter(AccessLevel.NONE)
    private String plainArgs;

    @Transient
    @Getter(AccessLevel.NONE)
    private String plainResult;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "result_delivered_at")
    private Instant resultDeliveredAt;

    /** 대화 purge에서 인자와 결과 및 실행 snapshot 본문을 지운 시각이다. 감사 칸은 남긴다. */
    @Column(name = "body_erased_at")
    private Instant bodyErasedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 주문 상한 변경 승인 줄만 채운다(ADR-20261015 / financial-user-limits). 서버가 줄을 만들 때 적고 바꾸지 않는다.
     * 공개 승인 API 는 이 컬럼이 있는 줄만 상한 변경 승인으로 보낸다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "limit_kind", length = 32, updatable = false)
    private FinancialLimitKind limitKind;

    /** 상한 변경 승인 줄에서 요청한 값이다. 바꾸지 않는다. */
    @Column(name = "limit_requested", precision = 20, scale = 6, updatable = false)
    private BigDecimal limitRequested;

    /** 상한 변경 승인 줄을 만들 때 적용하던 상한이다. 카드가 바뀌기 전 값을 보인다. 바꾸지 않는다. */
    @Column(name = "limit_previous", precision = 20, scale = 6, updatable = false)
    private BigDecimal limitPrevious;


    /**
     * 판정 한 줄이다. 인자 원문과 승인 상태는 담지 않는다.
     *
     * @param agentId 판정한 실행의 에이전트. 그 에이전트에 붙은 연결로 판정했다
     * @param toolName 등록 이름과 맞는 것을 확인한 원래 도구 이름. 확인하지 못했으면 null
     * @param passed hook 에 통과로 답했는가
     */
    public static ConnectorAction decided(
        ConnectorConnection connection,
        Long agentId,
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
        action.agentId = agentId;
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


    /** 주문 상한 변경 승인 줄이라고 적는다. 저장하기 전에만 부른다. 저장한 뒤의 컬럼은 고치기에서 빠져 바뀌지 않는다. */
    public void recordLimitChange(FinancialLimitKind kind, BigDecimal requested, BigDecimal previous) {
        if (id != null || limitKind != null) {
            throw new IllegalStateException("limit change is recorded only once before insert");
        }
        this.limitKind = Objects.requireNonNull(kind);
        this.limitRequested = Objects.requireNonNull(requested);
        this.limitPrevious = Objects.requireNonNull(previous);
    }


    /** 먼저 살펴보기 트리의 실행에서 나온 줄이라고 적는다. 저장하기 전에만 부른다. */
    public void markCheckTree() {
        this.checkTree = true;
    }


    /**
     * 판정 줄을 승인 줄로 만든다. 저장하기 전에만 부른다.
     *
     * <p>인자는 번호를 받은 뒤 같은 트랜잭션에서 암호화해 적는다. INSERT에는 인자 원문을 싣지 않는다.
     */
    public void awaitApproval(Instant expiresAt) {
        if (decision != ActionDecision.NEEDS_APPROVAL || passed || status != null) {
            throw new IllegalStateException("only a blocked NEEDS_APPROVAL action can await approval");
        }
        this.status = ActionStatus.PENDING;
        this.expiresAt = expiresAt;
    }


    /** 승인했다. 이 전이를 커밋한 뒤에만 실행을 보낸다. */
    public void beginExecution(Instant now) {
        require(ActionStatus.PENDING);
        this.status = ActionStatus.EXECUTING;
        this.decidedAt = now;
    }


    /** 저장용 본문과 key를 함께 받는다. 평문 봉인은 호출 전에 끝나야 한다. */
    public void succeed(String storedResult, Long keyId, Instant now) {
        require(ActionStatus.EXECUTING);
        this.status = ActionStatus.SUCCEEDED;
        sealResult(storedResult, keyId);
        this.executedAt = now;
    }


    /**
     * @param errorCode 공통 오류 어휘의 글자
     * @param storedResult 저장할 오류 계약의 본문. 암호화가 켜져 있으면 이미 봉인한 글이다. 없으면 null
     */
    public void fail(String errorCode, String storedResult, Long keyId, Instant now) {
        require(ActionStatus.EXECUTING);
        this.status = ActionStatus.FAILED;
        this.errorCode = errorCode;
        sealResult(storedResult, keyId);
        this.executedAt = now;
    }


    /** 실행을 보냈으나 결과를 모른다. 다시 실행하지 않는다. */
    public void unknown(Instant now) {
        require(ActionStatus.EXECUTING);
        this.status = ActionStatus.UNKNOWN;
        this.executedAt = now;
    }


    /**
     * 결과를 모르는 줄에 커넥터가 선언한 코드와 재시도 식별자를 함께 남긴다(ADR-20261013 unknown-retry-key).
     *
     * @param storedResult 저장할 글. 암호화가 켜져 있으면 이미 봉인한 글이다
     */
    public void unknown(String storedResult, Long keyId, Instant now) {
        unknown(now);
        sealResult(storedResult, keyId);
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
        return bodyErasedAt == null && approvalMode == ToolApproval.REQUIRED && toolName != null;
    }


    /** 본문 접근은 모두 이 두 접근자를 거친다. 암호문은 처음 꺼낼 때만 푼다. */
    public String argsJson() {
        if (bodyErasedAt != null) {
            return BODY_ERASED;
        }
        if (argsKeyId == null) {
            return storedArgsJson;
        }
        if (plainArgs == null) {
            String opened = opener == null ? null : opener.open(this, "args_json", argsKeyId, storedArgsJson);
            plainArgs = opened == null ? UNREADABLE_ARGS : opened;
        }
        return plainArgs;
    }


    /** 풀 수 있고 저장 해시와 일치하는 실행 인자만 준다. 화면은 argsJson을 쓴다. */
    public Optional<String> executableArgs() {
        return executableArgs(Sha256::hex);
    }


    /** 금융 줄처럼 저장 해시를 keyed hash 로 적은 줄은 그 함수로 대조한다. */
    public Optional<String> executableArgs(UnaryOperator<String> hash) {
        if (bodyErasedAt != null) {
            return Optional.empty();
        }
        String plain = argsJson();
        if (plain == null || UNREADABLE_ARGS.equals(plain) || !hash.apply(plain).equals(argsSha256)) {
            return Optional.empty();
        }
        return Optional.of(plain);
    }


    public String resultText() {
        if (bodyErasedAt != null) {
            return BODY_ERASED;
        }
        if (resultKeyId == null) {
            return storedResultText;
        }
        if (plainResult == null) {
            String opened = opener == null ? null : opener.open(this, "result_text", resultKeyId, storedResultText);
            plainResult = opened == null ? UNREADABLE_RESULT : opened;
        }
        return plainResult;
    }


    public void attachOpener(ConnectorActionBodyOpener opener) {
        this.opener = opener;
    }


    /** key가 null이면 이관 전이거나 암호화를 쓰지 않은 저장이다. 푼 글은 저장할 때마다 버린다. */
    public void sealArgs(String stored, Long keyId) {
        this.storedArgsJson = stored;
        this.argsKeyId = keyId;
        this.plainArgs = null;
    }


    public void sealResult(String stored, Long keyId) {
        this.storedResultText = stored;
        this.resultKeyId = keyId;
        this.plainResult = null;
    }


    /**
     * 이 도구를 이 대화에서 계속 허용할 수 있는가(ADR-20261012 / conversation-grant). 상시 허락과 같은 조건에 더해, 허락이 묶일
     * 대화와 에이전트가 있어야 한다. 먼저 살펴보기 트리의 줄은 그 트리의 호출이 허락을 보지 않으므로 줄 수 없다.
     */
    public boolean conversationGrantAllowed() {
        return grantAllowed() && conversationId != null && agentId != null && !checkTree;
    }


    private void require(ActionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("connector action must be " + expected + " but is " + status);
        }
    }
}
