package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.model.AgentPurgeOutcome;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Flyway 가 만든 실제 MySQL 의 FK 아래에서 지운 에이전트를 지운다. 상위 클래스의 검사가 모두 여기서도 돈다. */
@Tag("mysql")
class AgentPurgeWriterMysqlTest extends AgentPurgeWriterTest {

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    @DisplayName("살펴보기의 자식 줄은 FK 가 함께 지우거나 비운다")
    void cascadesCheckChildrenThroughForeignKeys() {
        Attached attached = attachEverything();
        jdbc.update(
                "INSERT INTO proactive_check_finding (check_id, conversation_id, kind, area, title, created_at)"
                        + " VALUES (?, ?, 'NEW', 'test-area', '가상 발견', CURRENT_TIMESTAMP(6))",
                attached.checkId(),
                attached.conversationId());
        jdbc.update(
                "INSERT INTO decision_feedback_event"
                        + " (user_id, subject_type, subject_key, event_type, actor, source_check_id, contract_version,"
                        + " occurred_at)"
                        + " VALUES (?, 'CHECK', ?, 'SURFACED', 'USER', ?, 1, CURRENT_TIMESTAMP(6))",
                owner.id(),
                "proactive_check:" + attached.checkId(),
                attached.checkId());
        markAgentDeleted(DELETED_AT);

        AgentPurgeOutcome outcome = writer.purge(agent.id(), CUTOFF);

        assertThat(outcome).isEqualTo(AgentPurgeOutcome.PURGED);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM proactive_check_finding WHERE check_id = ?",
                        Long.class,
                        attached.checkId()))
                .as("발견 줄은 살펴보기와 함께 지운다")
                .isZero();
        assertThat(jdbc.queryForList(
                        "SELECT source_check_id FROM decision_feedback_event WHERE user_id = ?",
                        Long.class,
                        owner.id()))
                .as("판단 피드백 줄은 남고 살펴보기 칸만 비운다")
                .singleElement()
                .isNull();
    }
}
