package db.migration;

import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 일정의 일대일 관계와 발화 참조를 확인한 뒤 본체에 일정 컬럼을 준비한다. H2에서도 같은 사전 검사를 실행한다. */
public class V20261016224030__TaskTriggerIntoTask extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            requireValid(statement, """
                    SELECT t.id FROM task t LEFT JOIN task_trigger s ON s.task_id = t.id
                    GROUP BY t.id HAVING COUNT(s.id) <> 1
                    """);
            requireValid(statement, """
                    SELECT r.id FROM task_run r LEFT JOIN task_trigger s ON s.id = r.trigger_id
                    WHERE s.id IS NULL OR r.task_id <> s.task_id
                    """);
            for (String column : List.of(
                "trigger_type VARCHAR(20) NULL COMMENT '일정 종류: CRON 반복, ONCE 한 번'",
                "cron_expr VARCHAR(100) NULL COMMENT '반복 일정의 5필드 cron, ONCE면 NULL'",
                "fire_at DATETIME(6) NULL COMMENT '한 번 실행할 UTC 시각, CRON이면 NULL'",
                "time_zone VARCHAR(64) NULL COMMENT '일정을 해석하는 IANA 시간대 이름'",
                "missed_policy VARCHAR(20) NULL COMMENT '놓친 발화 처리: SKIP 건너뜀, RUN_ONCE 한 번 실행'",
                "next_fire_at DATETIME(6) NULL COMMENT '다음 예정 UTC 시각, 끝난 ONCE나 다음 일정이 없으면 NULL'",
                "last_fired_at DATETIME(6) NULL COMMENT '마지막으로 처리한 예정 UTC 시각, 처리 전이면 NULL'")) {
                statement.execute("ALTER TABLE task ADD COLUMN " + column);
            }
        }
    }


    private static void requireValid(Statement statement, String query) throws Exception {
        try (var rows = statement.executeQuery(query)) {
            if (rows.next()) {
                throw new IllegalStateException("task schedule migration requires one matching schedule per task");
            }
        }
    }
}
