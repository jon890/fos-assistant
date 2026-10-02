package com.bifos.assistant.usage.domain;

import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * native 자식 한 명의 사용량 원장 줄이자, 그 사용량을 session 에서 조회하는 작업이다.
 *
 * <p>합계에 더할 토큰과 금액은 이 줄에 한 번만 적는다. 근거는 ADR-059 에 있다.
 */
@Entity
@Table(
        name = "subagent_usage_job",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_subagent_usage_child",
                    columnNames = {"execution_id", "child_session_id"})
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubagentUsageJob {
    private static final int PROVIDER_LIMIT = 64;
    private static final int MODEL_LIMIT = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "child_session_id", nullable = false, length = 128)
    private String childSessionId;

    @Column(name = "parent_session_id", length = 128)
    private String parentSessionId;

    @Column(name = "profile_name", nullable = false, length = 64)
    private String profileName;

    @Column(name = "api_base_url", nullable = false, length = 512)
    private String apiBaseUrl;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "unconfirmed_reason", length = 32)
    private String unconfirmedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "backoff_attempts", nullable = false)
    private int backoffAttempts;

    @Column(name = "provider", length = PROVIDER_LIMIT)
    private String provider;

    @Column(name = "model", length = MODEL_LIMIT)
    private String model;

    /** cache 를 뺀 일반 입력 토큰이다. */
    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "cache_read_tokens")
    private Long cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private Long cacheWriteTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "estimated_cost_micros")
    private Long estimatedCostMicros;

    @Column(name = "actual_cost_micros")
    private Long actualCostMicros;

    @Column(name = "cost_currency", length = 3, columnDefinition = "CHAR(3)")
    private String costCurrency;

    @Column(name = "pricing_version", length = 32)
    private String pricingVersion;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    public static SubagentUsageJob create(AgentExecution parent, ExecutionEvent start, String apiBaseUrl, Instant now) {
        SubagentUsageJob job = new SubagentUsageJob();
        job.executionId = parent.id();
        job.childSessionId = start.hermesSessionId();
        job.parentSessionId = parent.hermesSessionId();
        job.profileName = parent.profileName();
        job.apiBaseUrl = apiBaseUrl;
        job.status = "WAITING";
        job.createdAt = now;
        job.nextAttemptAt = now;
        job.expiresAt = parent.finishedAt().plus(Duration.ofHours(24));
        return job;
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * 종료를 확인한 자식의 사용량과 금액을 적고 줄을 끝낸다.
     *
     * <p>금액을 내지 못했으면 {@code cost} 는 비어 있고 {@code reason} 에 그 까닭이 온다.
     */
    public void record(SubagentSessionUsage usage, ExecutionCost cost, String reason, Instant now) {
        provider =
                usage.provider() == null || usage.provider().isBlank() ? null : cut(usage.provider(), PROVIDER_LIMIT);
        model = cut(usage.model(), MODEL_LIMIT);
        inputTokens = usage.inputTokens();
        cacheReadTokens = usage.cacheReadTokens();
        cacheWriteTokens = usage.cacheWriteTokens();
        outputTokens = usage.outputTokens();
        estimatedCostMicros = cost.estimatedMicros();
        actualCostMicros = cost.actualMicros();
        costCurrency = cost.currency();
        pricingVersion = cost.pricingVersion();
        recordedAt = now;
        status = "DONE";
        unconfirmedReason = reason;
    }

    /**
     * 칸 길이를 넘는 값을 잘라 적는다.
     *
     * <p>넘는 값을 그대로 저장하면 저장이 실패해 다음 조회 시각도 함께 되돌려지고, 그 줄이 기한까지 계속 다시
     * 조회된다.
     */
    private static String cut(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    public void expire() {
        expire("DEADLINE");
    }

    public void expire(String reason) {
        status = "EXPIRED";
        unconfirmedReason = reason;
    }

    public void retry(Instant now) {
        attempts++;
        long seconds = 5;
        if (!now.isBefore(createdAt.plusSeconds(120))) {
            backoffAttempts = Math.min(backoffAttempts + 1, 6);
            seconds = Math.min(300, 5L << backoffAttempts);
        }
        nextAttemptAt = now.plusSeconds(seconds);
    }
}
