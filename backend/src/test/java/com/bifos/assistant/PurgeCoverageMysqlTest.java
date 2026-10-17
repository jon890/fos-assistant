package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentPurgeWriter;
import com.bifos.assistant.agent.application.dto.AgentPurgeOutcome;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.purge.application.ConversationPurger;
import com.bifos.assistant.chat.attachment.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.purge.application.port.ConversationActionPurge;
import com.bifos.assistant.chat.attachment.application.dto.ChatContentMutationTarget;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorFinancialSummaries;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import com.bifos.assistant.testsupport.PurgeTable;
import com.bifos.assistant.testsupport.PurgeTable.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Flyway가 만든 실제 MySQL 스키마에 모든 테이블의 행을 넣고 운영 삭제 경로를 실행한다.
 *
 * <p>FK와 CHECK를 켠 채로 검사하며, 보존 대상과 다른 사용자의 행은 전체 칸을 비교한다. 사용자 물리 삭제는 구현되지 않았으므로
 * 접근 해제를 실행한 뒤 알려진 공백을 확인한다. Hermes는 기존 통합 테스트의 대역을 쓰며 파일 원본은 만들지 않는다.
 */
@Tag("mysql")
@BackendIntegrationTest
class PurgeCoverageMysqlTest {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final AtomicLong IDS = new AtomicLong(900000001L);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChatService chat;

    @Autowired
    private ConversationPurger conversations;

    @Autowired
    private AgentPurgeWriter agents;

    @Autowired
    private PersonAccessService people;

    @Autowired
    private ChatContentMutationCoordinator mutations;

    @Autowired
    private ConversationActionPurge actionPurge;

    @Autowired
    private ConnectorActionRepository actions;

    @Autowired
    private ConnectorActionService actionQueries;

    @Autowired
    private ConnectorFinancialSummaries financialSummaries;

    private List<PurgeTable> tables;
    private long target;
    private long other;
    private Map<String, List<Map<String, Object>>> before;
    private Map<String, List<Map<String, Object>>> untouched;


    @DynamicPropertySource
    static void useMysql(DynamicPropertyRegistry registry) {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }


    @BeforeEach
    void setUp() throws IOException {
        PurgeTable.assertSchema(jdbc);
        tables = PurgeTable.readAll();
        target = IDS.getAndAdd(10);
        other = target + 1;
        insertFixture(target);
        insertFixture(other);
        before = new LinkedHashMap<>();
        untouched = new LinkedHashMap<>();
        for (PurgeTable table : tables) {
            List<Map<String, Object>> targetRows = rows(table, target);
            assertThat(targetRows).as("%s의 삭제 대상이 비어 있으면 검사가 되지 않는다", table.table()).hasSize(1);
            before.put(table.table(), targetRows);
            List<Map<String, Object>> rows = rows(table, other);
            assertThat(rows).as("%s의 다른 사용자 데이터도 삭제 전에 있어야 한다", table.table()).hasSize(1);
            untouched.put(table.table(), rows);
        }
    }


    @Test
    @DisplayName("접근 해제 뒤 사용자 purge의 알려진 공백은 남는다")
    void recordsMissingUserPurge() {
        people.setEnabled(target, false);

        for (PurgeTable table : tables) {
            assertOutcome(table, table.user());
        }
        assertThat(value("allowed_person", "enabled")).isEqualTo(false);
        assertOtherUntouched();
    }


    @Test
    @DisplayName("대화를 지운 뒤 모든 테이블의 삭제·본문 제거·보존과 알려진 공백을 확인한다")
    void coversEveryTableAfterConversationPurge() {
        assertThat(value("memory", "status")).isEqualTo("ACCEPTED");
        assertThat(value("memory", "accepted_by_user_id")).isEqualTo(target);
        assertThat(value("memory", "accepted_at")).isNotNull();
        chat.delete(currentUser(), target);
        assertThat(conversations.purgeDue(NOW)).isEqualTo(1);

        for (PurgeTable table : tables) {
            assertOutcome(table, table.conversation());
            if (table.conversation() == Outcome.GAP) {
                assertThat(rows(table, target)).as("%s의 알려진 공백이 달라졌으면 기준을 다시 검토한다", table.table())
                    .usingRecursiveComparison().isEqualTo(before.get(table.table()));
            }
        }
        assertThat(value("agent_execution", "estimated_cost_micros")).isEqualTo(123L);
        assertThat(value("agent_execution", "input_tokens")).isEqualTo(17L);
        assertErasedAt(NOW);
        ConnectorAction action = actions.findById(target).orElseThrow();
        assertThat(action.argsJson()).isEqualTo(ConnectorAction.BODY_ERASED);
        assertThat(action.executableArgs()).isEmpty();
        assertThat(actionQueries.resultsFor(target, target, List.of(action.publicId()))).singleElement()
            .satisfies(result -> assertThat(result.resultText()).isEqualTo(ConnectorAction.BODY_ERASED));
        assertOtherUntouched();
    }


    @ParameterizedTest
    @EnumSource(value = ActionStatus.class, names = {"PENDING", "EXECUTING", "UNKNOWN"})
    @DisplayName("열린 승인과 금융 실행은 본문을 보존하고 대화의 다른 본문은 지운다")
    void defersOpenActionBodiesAndRetriesWhenClosed(ActionStatus status) {
        jdbc.update("UPDATE connector_action SET status = ?, risk = 'FINANCIAL' WHERE id = ?", status.name(), target);
        refreshActionBefore();
        long closed = insertClosedAction(target);
        chat.delete(currentUser(), target);

        assertThat(conversations.purgeDue(NOW)).isEqualTo(1);
        assertThat(value("conversation", "purged_at")).isNotNull();
        assertThat(rows(classified("chat_message"), target)).isEmpty();
        assertActionRowsUnchanged();
        assertThat(jdbc.queryForMap("SELECT args_json, result_text, body_erased_at FROM connector_action WHERE id = ?", closed))
            .containsEntry("args_json", null).containsEntry("result_text", null)
            .doesNotContainEntry("body_erased_at", null);
        assertThat(conversations.purgeDue(NOW.plusSeconds(60))).isZero();

        jdbc.update("UPDATE connector_action SET status = 'REJECTED' WHERE id = ?", target);
        refreshActionBefore();
        assertThat(conversations.purgeDue(NOW.plusSeconds(120))).isEqualTo(1);
        assertOutcome(classified("connector_action"), Outcome.SCRUB);
        assertOutcome(classified("connector_action_execution"), Outcome.SCRUB);
        assertErasedAt(NOW.plusSeconds(120));
        assertOtherUntouched();
    }


    @ParameterizedTest
    @EnumSource(value = ActionStatus.class, names = {"SUCCEEDED", "FAILED", "REJECTED", "EXPIRED"})
    @DisplayName("끝난 일반 승인 줄은 상태를 바꾸지 않고 두 테이블의 본문만 지운다")
    void erasesClosedActionBodiesWithoutChangingAuditFields(ActionStatus status) {
        jdbc.update("UPDATE connector_action SET status = ? WHERE id = ?", status.name(), target);
        refreshActionBefore();
        chat.delete(currentUser(), target);

        assertThat(conversations.purgeDue(NOW)).isEqualTo(1);
        assertOutcome(classified("connector_action"), Outcome.SCRUB);
        assertOutcome(classified("connector_action_execution"), Outcome.SCRUB);
        assertOtherUntouched();
    }


    @Test
    @DisplayName("성공한 당일 금융 본문은 KST 자정 직전까지 보존하고 다음 날 지운다")
    void retainsSuccessfulFinancialBodiesUntilNextKstDay() {
        Instant nextDay = Instant.parse("2026-10-16T15:00:00Z");
        jdbc.update("UPDATE connector_action SET risk = 'FINANCIAL', decided_at = ? WHERE id = ?",
            NOW.atOffset(ZoneOffset.UTC).toLocalDateTime(), target);
        refreshActionBefore();
        chat.delete(currentUser(), target);

        assertThat(conversations.purgeDue(NOW)).isEqualTo(1);
        assertThat(conversations.purgeDue(nextDay.minusSeconds(1))).isZero();
        assertActionRowsUnchanged();

        assertThat(conversations.purgeDue(nextDay)).isEqualTo(1);
        assertOutcome(classified("connector_action"), Outcome.SCRUB);
        assertOutcome(classified("connector_action_execution"), Outcome.SCRUB);
        assertErasedAt(nextDay);
        ConnectorAction action = actions.findById(target).orElseThrow();
        assertThat(financialSummaries.summaryOf(currentUser(), action.publicId())).get()
            .satisfies(summary -> assertThat(summary.available()).isFalse());
        assertOtherUntouched();
    }


    @Test
    @DisplayName("본문 삭제 도입 전에 이미 purge한 대화도 원래 purge 시각을 보존하며 정리한다")
    void erasesBodiesFromPreviouslyPurgedConversation() {
        Instant previous = NOW.minusSeconds(86400);
        chat.delete(currentUser(), target);
        jdbc.update("UPDATE conversation SET purged_at = ? WHERE id = ?", Timestamp.from(previous), target);

        assertThat(conversations.purgeDue(NOW)).isEqualTo(1);
        assertOutcome(classified("connector_action"), Outcome.SCRUB);
        assertOutcome(classified("connector_action_execution"), Outcome.SCRUB);
        assertThat(jdbc.queryForObject("SELECT purged_at FROM conversation WHERE id = ?", Timestamp.class, target))
            .isEqualTo(Timestamp.from(previous));
        assertThat(conversations.purgeDue(NOW)).isZero();
        assertOtherUntouched();
    }


    @Test
    @DisplayName("실행 중인 삭제 대화가 배치를 채워도 이미 purge한 대화의 승인 본문을 정리한다")
    void retriesActionBodiesWhenRunningConversationsFillBatch() {
        chat.delete(currentUser(), target);
        jdbc.update("UPDATE conversation SET purged_at = ? WHERE id = ?", Timestamp.from(NOW.minusSeconds(60)), target);
        long first = IDS.getAndAdd(50);
        try {
            for (long id = first; id < first + 50; id++) {
                jdbc.update("""
                        INSERT INTO conversation (id, user_id, agent_id, title, created_at, updated_at, public_id, deleted_at)
                        VALUES (?, ?, ?, '실행 중인 대화', ?, ?, UNHEX(LPAD(HEX(?), 32, '0')), ?)
                        """, id, target, target, Timestamp.from(NOW), Timestamp.from(NOW), id, Timestamp.from(NOW));
                jdbc.update("""
                        INSERT INTO agent_execution (id, user_id, conversation_id, agent_id, profile_name, cost_mode, status, started_at)
                        VALUES (?, ?, ?, ?, 'purge-batch-test', 'SUBSCRIPTION', 'RUNNING', ?)
                        """, id, target, id, target, Timestamp.from(NOW));
            }

            assertThat(conversations.purgeDue(NOW)).isEqualTo(1);
            assertOutcome(classified("connector_action"), Outcome.SCRUB);
            assertOutcome(classified("connector_action_execution"), Outcome.SCRUB);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE id >= ? AND id < ? AND purged_at IS NULL",
                Integer.class, first, first + 50)).isEqualTo(50);
            assertOtherUntouched();
        } finally {
            jdbc.update("DELETE FROM agent_execution WHERE id >= ? AND id < ?", first, first + 50);
            jdbc.update("DELETE FROM conversation WHERE id >= ? AND id < ?", first, first + 50);
        }
    }


    @Test
    @DisplayName("본문 삭제의 뒤 단계가 실패하면 삭제 표시와 두 테이블의 본문을 모두 롤백한다")
    void rollsBackBothActionBodiesTogether() {
        chat.delete(currentUser(), target);

        assertThatThrownBy(() -> mutations.run(target, new ChatContentMutationTarget(target, List.of()), () -> {
            assertThat(actionPurge.eraseBodies(target, NOW)).isEqualTo(1);
            throw new IllegalStateException("뒤 단계 실패 대역");
        })).isInstanceOf(IllegalStateException.class);

        assertActionRowsUnchanged();
        assertThat(conversations.purgeDue(NOW)).isEqualTo(1);
        assertOutcome(classified("connector_action"), Outcome.SCRUB);
        assertOutcome(classified("connector_action_execution"), Outcome.SCRUB);
        assertOtherUntouched();
    }


    @Test
    @DisplayName("에이전트 지연 정리는 모든 하위 표를 지우거나 참조를 풀고 사용량과 사용자 데이터는 남긴다")
    void coversEveryTableAfterAgentPurge() {
        jdbc.update("UPDATE agent SET deleted_at = ?, enabled = false WHERE id = ?", NOW.minusSeconds(864000), target);

        assertThat(agents.purge(target, NOW.minusSeconds(604800))).isEqualTo(AgentPurgeOutcome.PURGED);

        for (PurgeTable table : tables) {
            assertOutcome(table, table.agent());
        }
        assertOtherUntouched();
    }


    private void insertFixture(long id) throws IOException {
        String sql = new ClassPathResource("purge/fixture.sql").getContentAsString(StandardCharsets.UTF_8)
            .replace("900000001", Long.toString(id));
        jdbc.execute((ConnectionCallback<Void>)connection -> {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            return null;
        });
    }


    private void assertOutcome(PurgeTable table, Outcome outcome) {
        assertThat(rows(table, target))
            .as("%s: %s (%s). 새 공백은 %s에 근거와 함께 명시한다", table.table(), outcome, table.reason(), PurgeTable.RESOURCE)
            .hasSize(outcome == Outcome.DELETE ? 0 : 1);
        switch (outcome) {
            case KEEP -> assertThat(rows(table, target)).as("%s의 보존 대상은 모든 칸이 유지돼야 한다", table.table())
                .usingRecursiveComparison().isEqualTo(before.get(table.table()));
            case DETACH -> {
                List<String> columns = table.table().equals("decision_feedback_event")
                    ? List.of("source_check_id", "autonomy_decision_id") : List.of("agent_id");
                columns.forEach(column -> assertCleared(table.table(), column));
            }
            case GONE -> {
                assertThat(value(table.table(), "status")).isEqualTo("GONE");
                assertCleared(table.table(), "request_hash");
                assertCleared(table.table(), "draft_public_id");
            }
            case SCRUB -> {
                switch (table.table()) {
                    case "conversation" -> {
                        assertThat(before.get("conversation").getFirst().get("title")).isNotEqualTo("");
                        assertThat(value("conversation", "title")).isEqualTo("");
                        assertCleared("conversation", "hermes_session_id");
                        assertCleared("conversation", "hermes_root_session_id");
                        assertThat(value("conversation", "purged_at")).isNotNull();
                    }
                    case "agent_execution" -> assertCleared("agent_execution", "output_text");
                    case "execution_event" -> assertCleared("execution_event", "detail");
                    case "connector_action" -> {
                        List<String> bodies = List.of("args_json", "args_key_id", "result_text", "result_key_id");
                        bodies.forEach(column -> assertCleared(table.table(), column));
                        assertThat(value(table.table(), "body_erased_at")).isNotNull();
                        assertPreservedExcept(table, List.of("args_json", "args_key_id", "result_text", "result_key_id", "body_erased_at"));
                    }
                    case "connector_action_execution" -> {
                        List<String> bodies = List.of("execution_args_json", "summary_json", "scope_json", "content_key_id");
                        bodies.forEach(column -> assertCleared(table.table(), column));
                        assertPreservedExcept(table, bodies);
                    }
                    default -> throw new AssertionError(table.table() + "의 SCRUB 대상 칸을 검사에 더한다");
                }
            }
            default -> {
            }
        }
    }


    private void assertCleared(String table, String column) {
        assertThat(before.get(table).getFirst().get(column)).as("%s.%s는 삭제 전에 값이 있어야 한다", table, column).isNotNull();
        assertThat(value(table, column)).as("%s.%s의 값이 남았다", table, column).isNull();
    }


    private void assertPreservedExcept(PurgeTable table, List<String> erasedColumns) {
        var expected = new LinkedHashMap<>(before.get(table.table()).getFirst());
        var actual = new LinkedHashMap<>(rows(table, target).getFirst());
        erasedColumns.forEach(column -> {
            expected.remove(column);
            actual.remove(column);
        });
        assertThat(actual).as("%s의 본문 외 모든 감사 칸을 보존한다", table.table())
            .usingRecursiveComparison().isEqualTo(expected);
    }


    private PurgeTable classified(String table) {
        return tables.stream().filter(entry -> entry.table().equals(table)).findFirst().orElseThrow();
    }


    private void refreshActionBefore() {
        before.put("connector_action", rows(classified("connector_action"), target));
    }


    private void assertActionRowsUnchanged() {
        for (String table : List.of("connector_action", "connector_action_execution")) {
            assertThat(rows(classified(table), target)).usingRecursiveComparison().isEqualTo(before.get(table));
        }
    }


    private void assertErasedAt(Instant now) {
        assertThat(actions.findById(target).orElseThrow().bodyErasedAt()).isEqualTo(now);
    }


    private long insertClosedAction(long conversationId) {
        long id = conversationId + 2;
        jdbc.update("""
                INSERT INTO connector_action (id, public_id, user_id, connector_id, hermes_tool, decision, passed,
                    status, origin_execution_id, conversation_id, dedupe_key, args_json, args_sha256, result_text,
                    created_at, check_tree, risk, args_key_id, result_key_id)
                SELECT ?, UNHEX(LPAD(HEX(?), 32, '0')), user_id, connector_id, hermes_tool, decision, passed,
                    'SUCCEEDED', origin_execution_id, conversation_id, CONCAT('closed-', dedupe_key), args_json,
                    args_sha256, result_text, created_at, check_tree, 'WRITE', args_key_id, result_key_id
                FROM connector_action WHERE id = ?
                """, id, id, conversationId);
        return id;
    }


    private List<Map<String, Object>> rows(PurgeTable table, long id) {
        return jdbc.queryForList("SELECT * FROM " + table.table() + " WHERE " + table.fixtureKey() + " = ?", id);
    }


    private Object value(String table, String column) {
        PurgeTable classified = tables.stream().filter(entry -> entry.table().equals(table)).findFirst().orElseThrow();
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE " + classified.fixtureKey() + " = ?",
            Object.class, target);
    }


    private CurrentUser currentUser() {
        return new CurrentUser(target, "coverage-" + target + "@example.test", "삭제 검사 사용자", target, UserRole.MEMBER);
    }


    private void assertOtherUntouched() {
        for (PurgeTable table : tables) {
            assertThat(rows(table, other)).as("%s의 다른 사용자 데이터", table.table())
                .usingRecursiveComparison().isEqualTo(untouched.get(table.table()));
        }
    }
}
