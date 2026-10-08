package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.application.ProactiveLoopRecovery;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 서버가 멈춰 남은 RUNNING 시도를 기동 복구가 닫는다. 평가와 판정을 다시 부르지 않는다. */
@BackendIntegrationTest
class ProactiveLoopRecoveryTest extends ProactiveLoopTestSupport {

    @Autowired
    ProactiveLoopRecovery recovery;

    @Test
    @DisplayName("RUNNING 시도는 FAILED/INTERRUPTED 로 닫히고 평가 번호가 비며 평가 수가 그대로다. 끝난 시도는 그대로다")
    void closesRunningRunsAsInterrupted() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Instant startedAt = BASE.minus(Duration.ofMinutes(5));
        ProactiveLoopRun stale = loopRuns.save(ProactiveLoopRun.running(user.id(), newCheck(user, agent), startedAt));
        ProactiveLoopRun finished = saveEarlierDecidedRun(user, agent, BASE.minus(Duration.ofHours(23)));
        long evaluationsBefore = evaluationCount(user);

        recovery.recover();

        ProactiveLoopRun closed = loopRuns.findById(stale.id()).orElseThrow();
        assertThat(closed.status()).as("닫은 시도 상태").isEqualTo(LoopRunStatus.FAILED);
        assertThat(closed.errorCode()).as("오류 코드").isEqualTo("INTERRUPTED");
        assertThat(closed.evaluationId()).as("평가 번호").isNull();
        assertThat(closed.createdAt()).as("저장 시각").isEqualTo(startedAt);
        assertThat(closed.finishedAt()).as("닫은 시각").isEqualTo(BASE);
        assertThat(loopRuns.findById(finished.id()).orElseThrow().status())
                .as("끝난 시도")
                .isEqualTo(LoopRunStatus.DECIDED);
        assertThat(evaluationCount(user)).as("평가 수").isEqualTo(evaluationsBefore);
    }

    private Long newCheck(CurrentUser user, Agent agent) {
        Long conversationId =
                checkConversations.findOrCreate(user, agent).conversation().id();
        ProactiveCheck check = ProactiveCheck.started(
                user.id(),
                agent.id(),
                conversationId,
                CheckTrigger.SCHEDULED,
                false,
                BASE.minus(Duration.ofMinutes(6)));
        check.succeed(CheckOutcome.FINDINGS, 1, 0, null, 0, 0, 0, 0, 0, BASE.minus(Duration.ofMinutes(5)));
        return checks.save(check).id();
    }
}
