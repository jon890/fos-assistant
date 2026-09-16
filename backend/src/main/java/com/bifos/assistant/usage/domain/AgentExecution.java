package com.bifos.assistant.usage.domain;

import com.bifos.assistant.credential.domain.CostMode;
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
 * One agent turn, recorded for usage and cost reporting.
 *
 * <p>Token counts are stored for every run. Cost is left null while a binding runs on a flat-rate
 * plan, and filled in later for metered API bindings once a price table exists. Money is kept as an
 * integer number of micro-units so no rounding happens in the database.
 */
@Entity
@Table(name = "agent_execution")
public class AgentExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "profile_name", nullable = false, length = 64)
    private String profileName;

    @Column(name = "hermes_run_id", length = 128)
    private String hermesRunId;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_mode", nullable = false, length = 20)
    private CostMode costMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "cached_input_tokens")
    private Long cachedInputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    /** Estimated spend in millionths of a currency unit. Null while the plan is flat-rate. */
    @Column(name = "estimated_cost_micros")
    private Long estimatedCostMicros;

    @Column(name = "cost_currency", length = 3)
    private String costCurrency;

    /** Identifies the price table used, so a later price change does not rewrite history. */
    @Column(name = "pricing_version", length = 32)
    private String pricingVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    protected AgentExecution() {
    }

    private AgentExecution(Builder builder) {
        this.userId = builder.userId;
        this.conversationId = builder.conversationId;
        this.profileName = builder.profileName;
        this.hermesRunId = builder.hermesRunId;
        this.provider = builder.provider;
        this.model = builder.model;
        this.costMode = builder.costMode;
        this.status = builder.status;
        this.errorCode = builder.errorCode;
        this.inputTokens = builder.inputTokens;
        this.cachedInputTokens = builder.cachedInputTokens;
        this.outputTokens = builder.outputTokens;
        this.totalTokens = builder.totalTokens;
        this.latencyMs = builder.latencyMs;
        this.startedAt = builder.startedAt;
        this.finishedAt = builder.finishedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public String profileName() {
        return profileName;
    }

    public String hermesRunId() {
        return hermesRunId;
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

    public ExecutionStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public Long inputTokens() {
        return inputTokens;
    }

    public Long cachedInputTokens() {
        return cachedInputTokens;
    }

    public Long outputTokens() {
        return outputTokens;
    }

    public Long totalTokens() {
        return totalTokens;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public Long estimatedCostMicros() {
        return estimatedCostMicros;
    }

    public String costCurrency() {
        return costCurrency;
    }

    public String pricingVersion() {
        return pricingVersion;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public static final class Builder {
        private Long userId;
        private Long conversationId;
        private String profileName;
        private String hermesRunId;
        private String provider;
        private String model;
        private CostMode costMode;
        private ExecutionStatus status;
        private String errorCode;
        private Long inputTokens;
        private Long cachedInputTokens;
        private Long outputTokens;
        private Long totalTokens;
        private long latencyMs;
        private Instant startedAt;
        private Instant finishedAt;

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder conversationId(Long conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder profileName(String profileName) {
            this.profileName = profileName;
            return this;
        }

        public Builder hermesRunId(String hermesRunId) {
            this.hermesRunId = hermesRunId;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder costMode(CostMode costMode) {
            this.costMode = costMode;
            return this;
        }

        public Builder status(ExecutionStatus status) {
            this.status = status;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder tokens(Long input, Long cachedInput, Long output, Long total) {
            this.inputTokens = input;
            this.cachedInputTokens = cachedInput;
            this.outputTokens = output;
            this.totalTokens = total;
            return this;
        }

        public Builder timing(Instant startedAt, Instant finishedAt) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
            return this;
        }

        public AgentExecution build() {
            return new AgentExecution(this);
        }
    }
}
