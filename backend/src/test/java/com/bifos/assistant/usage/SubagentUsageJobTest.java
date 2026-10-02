package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.SubagentUsageJob;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 서버 재기동 뒤에도 DB 행만으로 재시도할 수 있는 작업의 상태 전이를 고정한다. */
class SubagentUsageJobTest {

    @Test
    @DisplayName("처음 2분은 5초마다 다시 보고 그 뒤에는 최대 5분까지 늦춘다")
    void retriesWithCappedBackoff() throws ReflectiveOperationException {
        Instant finishedAt = Instant.parse("2026-10-01T00:00:00Z");
        SubagentUsageJob job = job(finishedAt);

        job.retry(finishedAt.plusSeconds(10));
        assertThat(job.nextAttemptAt()).isEqualTo(finishedAt.plusSeconds(15));

        Instant afterTwoMinutes = finishedAt.plusSeconds(121);
        Duration lastDelay = Duration.ZERO;
        for (int count = 0; count < 10; count++) {
            job.retry(afterTwoMinutes);
            lastDelay = Duration.between(afterTwoMinutes, job.nextAttemptAt());
            afterTwoMinutes = job.nextAttemptAt();
        }

        assertThat(lastDelay).isEqualTo(Duration.ofSeconds(300));
        assertThat(job.backoffAttempts()).isEqualTo(6);
    }

    @Test
    @DisplayName("부모 종료 24시간 뒤에는 작업을 만료로 표시한다")
    void expiresAfterTwentyFourHours() throws ReflectiveOperationException {
        Instant finishedAt = Instant.parse("2026-10-01T00:00:00Z");
        SubagentUsageJob job = job(finishedAt);

        assertThat(job.expired(finishedAt.plus(Duration.ofHours(24)).minusMillis(1)))
                .isFalse();
        assertThat(job.expired(finishedAt.plus(Duration.ofHours(24)))).isTrue();

        job.expire();
        assertThat(job.status()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("사용량과 금액을 적으면 칸을 옮기고 DONE 으로 바꾼다")
    void recordsUsageAndCostThenFinishes() throws ReflectiveOperationException {
        Instant finishedAt = Instant.parse("2026-10-01T00:00:00Z");
        SubagentUsageJob job = job(finishedAt);
        Instant recordedAt = finishedAt.plusSeconds(30);

        job.record(
                new SubagentSessionUsage(
                        "child-session",
                        "subagent",
                        "parent-session",
                        "example-fast",
                        "example-provider",
                        1.0,
                        2.0,
                        10L,
                        4L,
                        2L,
                        3L),
                new ExecutionCost(186L, 186L, "USD", "test-pricing@2026-10-01"),
                null,
                recordedAt);

        assertThat(job.status()).isEqualTo("DONE");
        assertThat(job.provider()).isEqualTo("example-provider");
        assertThat(job.model()).isEqualTo("example-fast");
        assertThat(job.inputTokens()).isEqualTo(10L);
        assertThat(job.cacheReadTokens()).isEqualTo(2L);
        assertThat(job.cacheWriteTokens()).isEqualTo(3L);
        assertThat(job.outputTokens()).isEqualTo(4L);
        assertThat(job.estimatedCostMicros()).isEqualTo(186L);
        assertThat(job.actualCostMicros()).isEqualTo(186L);
        assertThat(job.costCurrency()).isEqualTo("USD");
        assertThat(job.pricingVersion()).isEqualTo("test-pricing@2026-10-01");
        assertThat(job.recordedAt()).isEqualTo(recordedAt);
        assertThat(job.unconfirmedReason()).isNull();
    }

    @Test
    @DisplayName("공백 provider 는 비우고 금액 없는 줄에는 미확인 까닭을 적는다")
    void recordsBlankProviderAsNullWithReason() throws ReflectiveOperationException {
        Instant finishedAt = Instant.parse("2026-10-01T00:00:00Z");
        SubagentUsageJob job = job(finishedAt);

        job.record(
                new SubagentSessionUsage(
                        "child-session", "subagent", "parent-session", "example-fast", " ", 1.0, 2.0, 10L, 4L, 2L, 3L),
                ExecutionCost.unknown(),
                "PROVIDER_UNKNOWN",
                finishedAt);

        assertThat(job.status()).isEqualTo("DONE");
        assertThat(job.provider()).isNull();
        assertThat(job.estimatedCostMicros()).isNull();
        assertThat(job.actualCostMicros()).isNull();
        assertThat(job.costCurrency()).isNull();
        assertThat(job.pricingVersion()).isNull();
        assertThat(job.unconfirmedReason()).isEqualTo("PROVIDER_UNKNOWN");
    }

    private static SubagentUsageJob job(Instant finishedAt) throws ReflectiveOperationException {
        AgentExecution parent = AgentExecution.builder()
                .userId(1L)
                .agentId(2L)
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(finishedAt.minusSeconds(2))
                .hermesSessionId("parent-session")
                .build();
        setId(parent, 10L);
        setField(parent, "finishedAt", finishedAt);
        ExecutionEvent start = ExecutionEvent.builder()
                .executionId(parent.id())
                .sequence(1)
                .eventType(ExecutionEventType.SUBAGENT_STARTED)
                .hermesSessionId("child-session")
                .occurredAt(finishedAt.minusSeconds(1))
                .build();
        return SubagentUsageJob.create(parent, start, "http://runtime", finishedAt);
    }

    private static void setId(Object target, Long id) throws ReflectiveOperationException {
        setField(target, "id", id);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
