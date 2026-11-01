package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 기능 스위치 {@code assistant.proactive-suggestion.enabled} 를 끄면 제안 줄을 만들지 않고 발견과 문제 후보만 저장하는지 본다. */
@BackendIntegrationTest
@OverrideProperties("assistant.proactive-suggestion.enabled=false")
class ProactiveSuggestionDisabledTest extends ProactiveLoopTestSupport {

    @Autowired
    FollowUpRepository followUpRows;


    @Test
    @DisplayName("스위치가 꺼져 있으면 결과 블록에 제안이 있어도 제안 줄이 생기지 않고 발견과 문제 후보는 지금처럼 저장한다")
    void offKeepsCurrentBehavior() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        String output = candidateOutput()
            .replace(
                "\"report\":{",
                "\"suggestions\":[{\"id\":\"s1\",\"title\":\"예시 제안 하나\",\"detail\":{\"why\":\"합성\"}}],\"report\":{");

        Long checkId = wake(user, agent, output);

        assertThat(followUpRows.findAll().stream().filter(row -> row.userId().equals(user.id()))).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM proactive_check_finding WHERE check_id = ?", Long.class, checkId))
                .as("발견").isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM proactive_check_problem WHERE check_id = ?", Long.class, checkId))
                .as("문제 후보").isEqualTo(1L);
    }
}
