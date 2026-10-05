package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 매일 깨우기를 기존 예약 작업 발화기에 저장한다. */
public class V77__ProactiveScheduleTask extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean mysql = isMysql(connection.getMetaData());
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE task ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TURN'");
            statement.execute(
                    mysql
                            ? "ALTER TABLE task MODIFY COLUMN instruction TEXT NULL"
                            : "ALTER TABLE task ALTER COLUMN instruction SET NULL");
            addCheckKeyColumns(statement, mysql);
            statement.execute(
                    mysql
                            ? "ALTER TABLE task ADD UNIQUE KEY uk_task_check_owner_agent (check_owner_user_id, check_agent_id)"
                            : "ALTER TABLE task ADD CONSTRAINT uk_task_check_owner_agent UNIQUE (check_owner_user_id, check_agent_id)");
            statement.execute("ALTER TABLE task_run ADD COLUMN proactive_check_id BIGINT NULL");
            statement.execute("CREATE INDEX idx_task_run_proactive_check ON task_run (proactive_check_id)");
        }
    }

    private static void addCheckKeyColumns(Statement statement, boolean mysql) throws Exception {
        String ownerExpression = "CASE WHEN kind = 'CHECK' THEN owner_user_id ELSE NULL END";
        String agentExpression = "CASE WHEN kind = 'CHECK' THEN agent_id ELSE NULL END";
        if (mysql) {
            statement.execute("ALTER TABLE task ADD COLUMN check_owner_user_id BIGINT GENERATED ALWAYS AS ("
                    + ownerExpression
                    + ") STORED");
            statement.execute("ALTER TABLE task ADD COLUMN check_agent_id BIGINT GENERATED ALWAYS AS ("
                    + agentExpression
                    + ") STORED");
            return;
        }
        statement.execute(
                "ALTER TABLE task ADD COLUMN check_owner_user_id BIGINT GENERATED ALWAYS AS (" + ownerExpression + ")");
        statement.execute(
                "ALTER TABLE task ADD COLUMN check_agent_id BIGINT GENERATED ALWAYS AS (" + agentExpression + ")");
    }

    private static boolean isMysql(DatabaseMetaData metadata) throws Exception {
        return metadata.getDatabaseProductName()
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mysql");
    }
}
