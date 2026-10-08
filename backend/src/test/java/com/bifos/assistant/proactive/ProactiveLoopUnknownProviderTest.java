package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import com.bifos.assistant.testsupport.OverrideProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 설치되지 않은 provider 이름이면 평가가 거절되고 시도는 그 오류 코드로 끝난다. 다시 부르지 않는다. */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=missing-provider"})
class ProactiveLoopUnknownProviderTest extends ProactiveLoopTestSupport {

    @Test
    @DisplayName("모르는 provider 면 시도는 FAILED/VALIDATION_FAILED 이고 평가가 없다")
    void failsRunForUnknownProvider() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);

        Long checkId = wake(user, agent, candidateOutput());

        ProactiveLoopRun run = loopRuns.findBySourceCheckId(checkId).orElseThrow();
        assertThat(run.status()).as("시도 상태").isEqualTo(LoopRunStatus.FAILED);
        assertThat(run.errorCode()).as("오류 코드").isEqualTo("VALIDATION_FAILED");
        assertThat(run.evaluationId()).as("평가 번호").isNull();
        assertThat(run.finishedAt()).as("끝난 시각").isEqualTo(BASE);
        assertThat(evaluationCount(user)).as("평가 수").isZero();
        assertThat(decisionCount(user)).as("판정 수").isZero();
    }
}
