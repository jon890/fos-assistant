package db.migration;

import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 복사한 일정의 필수 제약과 발화의 유일 제약을 적용한 뒤 옛 일정 표를 없앤다. */
public class V20261016224032__TaskScheduleConstraints extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        boolean mysql = "MySQL".equals(context.getConnection().getMetaData().getDatabaseProductName());
        try (var statement = context.getConnection().createStatement()) {
            for (String column : List.of("trigger_type", "time_zone", "missed_policy")) {
                if (!mysql) {
                    statement.execute("ALTER TABLE task ALTER COLUMN " + column + " SET NOT NULL");
                }
            }
            if (mysql) {
                statement.execute("""
                        ALTER TABLE task
                            MODIFY COLUMN trigger_type VARCHAR(20) NOT NULL COMMENT '일정 종류: CRON 반복, ONCE 한 번',
                            MODIFY COLUMN time_zone VARCHAR(64) NOT NULL COMMENT '일정을 해석하는 IANA 시간대 이름',
                            MODIFY COLUMN missed_policy VARCHAR(20) NOT NULL COMMENT '놓친 발화 처리: SKIP 건너뜀, RUN_ONCE 한 번 실행'
                        """);
            }
            statement.execute("CREATE INDEX idx_task_due ON task (state, next_fire_at, id)");
            statement.execute("ALTER TABLE task_run ADD CONSTRAINT uk_task_run_task_scheduled UNIQUE (task_id, scheduled_for)");
            if (mysql) {
                statement.execute("ALTER TABLE task_run DROP FOREIGN KEY fk_task_run_trigger");
                statement.execute("DROP INDEX uk_task_run_trigger_scheduled ON task_run");
                statement.execute("DROP INDEX idx_task_run_task_scheduled ON task_run");
            } else {
                statement.execute("ALTER TABLE task_run DROP CONSTRAINT fk_task_run_trigger");
                statement.execute("ALTER TABLE task_run DROP CONSTRAINT uk_task_run_trigger_scheduled");
                statement.execute("DROP INDEX idx_task_run_task_scheduled");
            }
            statement.execute("ALTER TABLE task_run DROP COLUMN trigger_id");
            statement.execute("DROP TABLE task_trigger");
        }
    }
}
