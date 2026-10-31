package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 루프 설정의 쉬기 시각을 CHECK 작업으로 복사하는 DML 이 켜짐을 옮기지 않고 쉬기만 옮기는지 본다. */
class TaskSnoozeBackfillTest {

    private static final String BACKFILL = "db/migration/V20261031171506__task_snoozed_until_backfill.sql";


    @Test
    @DisplayName("켜진 설정의 쉬기 시각만 같은 사용자·에이전트의 CHECK 작업으로 복사하고 작업 상태는 바꾸지 않는다")
    void copiesSnoozeOnlyFromEnabledSettings() throws Exception {
        String url = "jdbc:h2:mem:task-snooze-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
            Statement sql = connection.createStatement()) {
            for (int user = 1; user <= 3; user++) {
                sql.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                    + " VALUES (95" + user + ", 'snooze" + user + "@example.com', '사용자', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
                sql.executeUpdate(
                    "INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode, credential_scope,"
                        + " visibility, owner_user_id, enabled, created_at) VALUES (96" + user + ", 'a" + user
                        + "', 'n', 'a" + user + "', 'http://localhost', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', 951, TRUE,"
                        + " CURRENT_TIMESTAMP(6))");
            }
            // 사용자 1: 켜진 설정과 켜진 작업. 쉬기가 복사된다.
            task(sql, 971, 951, 961, "ACTIVE", "CHECK");
            setting(sql, 951, 961, true, "2026-12-01 00:00:00");
            // 사용자 2: 켜진 옛 동의와 쉬기가 있지만 일정(작업)이 꺼져 있다. 쉬기는 복사되나 작업은 켜지지 않는다.
            task(sql, 972, 952, 962, "PAUSED", "CHECK");
            setting(sql, 952, 962, true, "2026-12-02 00:00:00");
            // 사용자 3: 꺼진 설정의 쉬기는 복사하지 않는다. 다른 종류의 작업도 건드리지 않는다.
            task(sql, 973, 953, 963, "ACTIVE", "CHECK");
            setting(sql, 953, 963, false, "2026-12-03 00:00:00");
            task(sql, 974, 951, 961, "ACTIVE", "TURN");

            sql.execute(new String(new ClassPathResource(BACKFILL).getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.startsWith("--"))
                .reduce("", (left, right) -> left + " " + right));

            assertThat(snooze(sql, 971)).isEqualTo("2026-12-01 00:00:00");
            assertThat(snooze(sql, 972)).isEqualTo("2026-12-02 00:00:00");
            assertThat(snooze(sql, 973)).isNull();
            assertThat(snooze(sql, 974)).as("CHECK 가 아닌 작업").isNull();
            assertThat(state(sql, 972)).as("옛 동의만으로 일정을 켜지 않는다").isEqualTo("PAUSED");
            assertThat(state(sql, 973)).isEqualTo("ACTIVE");
        }
    }


    private static void task(Statement sql, int id, int user, int agent, String state, String kind) throws Exception {
        sql.executeUpdate(
            "INSERT INTO task (id, public_id, owner_user_id, agent_id, title, instruction, kind, state, conversation_mode,"
                + " notify, trigger_type, cron_expr, time_zone, missed_policy, created_at, updated_at) VALUES (" + id
                + ", X'00000000000000000000000000000" + id + "', " + user + ", " + agent + ", '작업', NULL, '" + kind
                + "', '" + state + "', 'SINGLE', 'NEVER', 'CRON', '0 9 * * *', 'Asia/Seoul', 'SKIP',"
                + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
    }


    private static void setting(Statement sql, int user, int agent, boolean enabled, String snooze) throws Exception {
        sql.executeUpdate("INSERT INTO proactive_loop_setting (user_id, agent_id, enabled, snoozed_until, updated_at)"
            + " VALUES (" + user + ", " + agent + ", " + enabled + ", '" + snooze + "', CURRENT_TIMESTAMP(6))");
    }


    private static String snooze(Statement sql, int id) throws Exception {
        try (ResultSet rows = sql.executeQuery("SELECT snoozed_until FROM task WHERE id = " + id)) {
            rows.next();
            return rows.getString(1);
        }
    }


    private static String state(Statement sql, int id) throws Exception {
        try (ResultSet rows = sql.executeQuery("SELECT state FROM task WHERE id = " + id)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
