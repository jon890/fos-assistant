package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.purge.application.AgentPurgeWriter;
import com.bifos.assistant.agent.purge.application.model.AgentPurgeOutcome;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationPurger;
import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import com.bifos.assistant.testsupport.PurgeTable;
import com.bifos.assistant.testsupport.PurgeTable.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * <p>FK와 CHECK를 켠 채로 검사하며, 다른 사용자의 행은 전체 칸을 비교한다. 사용자 물리 삭제는 구현되지 않았으므로
 * 접근 해제를 실행한 뒤 알려진 공백과 토큰 폐기를 확인한다. Hermes와 파일 원본은 기존 통합 테스트의 대역을 쓴다.
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
    @DisplayName("접근 해제는 서비스 토큰을 폐기하지만 사용자 purge의 알려진 공백은 남는다")
    void recordsMissingUserPurgeAndRevokesAccess() {
        people.setEnabled(target, false);

        for (PurgeTable table : tables) {
            assertOutcome(table, table.user());
        }
        assertThat(value("allowed_person", "enabled")).isEqualTo(false);
        assertThat(value("service_token", "revoked_at")).isNotNull();
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
            case DETACH -> assertCleared(table.table(), "agent_id");
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
