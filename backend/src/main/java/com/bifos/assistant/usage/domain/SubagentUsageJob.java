package com.bifos.assistant.usage.domain;

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

/** 부모 종료 뒤에도 남아 있는 자식의 사용량 조회 작업이다. */
@Entity
@Table(name = "subagent_usage_job", uniqueConstraints = {
        @UniqueConstraint(name = "uk_subagent_usage_child", columnNames = {"execution_id", "child_session_id"})})
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubagentUsageJob {
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

    public void done() {
        status = "DONE";
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
