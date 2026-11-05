package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.dto.AgentPurgeOutcome;
import com.bifos.assistant.testsupport.MysqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Flyway 가 만든 실제 MySQL 의 FK 아래에서 지운 에이전트를 지운다. 상위 클래스에서는 FK 가 걸린 검사만 여기서도 돈다. */
@MysqlIntegrationTest
class AgentPurgeWriterMysqlTest extends AgentPurgeWriterTest {

    @Test
    @DisplayName("에이전트 정리는 살펴보기 줄을 지우고 판단 피드백 줄은 그대로 둔다")
    void deletesChecksAndKeepsFeedbackEvents() {
        Attached attached = attachEverything();
        jdbc.update(
            "INSERT INTO decision_feedback_event"
                + " (user_id, subject_type, subject_key, event_type, actor, contract_version, occurred_at)"
                + " VALUES (?, 'CHECK', ?, 'SURFACED', 'USER', 1, CURRENT_TIMESTAMP(6))",
            owner.id(),
            "proactive_check:" + attached.checkId());
        markAgentDeleted(DELETED_AT);

        AgentPurgeOutcome outcome = writer.purge(agent.id(), CUTOFF);

        assertThat(outcome).isEqualTo(AgentPurgeOutcome.PURGED);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM proactive_check WHERE id = ?", Long.class, attached.checkId()))
                .as("살펴보기 줄은 에이전트와 함께 지운다")
                .isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM decision_feedback_event WHERE user_id = ?", Long.class, owner.id()))
                .as("판단 피드백 줄은 남는다")
                .isEqualTo(1L);
    }
}
