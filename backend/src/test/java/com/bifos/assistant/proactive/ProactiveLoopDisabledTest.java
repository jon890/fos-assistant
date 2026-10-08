package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 설치 설정의 기본값(꺼짐)에서는 사용자가 켠 설정 줄이 있어도 매일 루프를 잇지 않는다. */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
class ProactiveLoopDisabledTest extends ProactiveLoopTestSupport {

    @Test
    @DisplayName("설치 설정이 꺼져 있으면 켜진 설정 줄이 있어도 시도 줄과 평가가 없다")
    void leavesNoRunWhenInstallationDisabled() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);

        Long checkId = wake(user, agent, candidateOutput());

        assertThat(checks.findById(checkId).orElseThrow().status())
                .as("살펴보기 상태")
                .isEqualTo(CheckStatus.SUCCEEDED);
        assertThat(runsOf(user)).as("시도 줄").isEmpty();
        assertThat(evaluationCount(user)).as("평가 수").isZero();
    }
}
