package db.migration;

import com.bifos.assistant.hermes.ToolDetailRedactor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 기존 도구 내용에도 같은 가리기 규칙을 적용한다. 원문은 복원할 수 없어 배포 전에 백업해야 한다. */
public class V41__RedactToolDetails extends BaseJavaMigration {
    private static final String SELECT_DETAILS = """
            SELECT e.id, e.execution_id, e.detail,
                   CASE WHEN a.connector_managed = TRUE OR p.connector_managed = TRUE
                        THEN TRUE ELSE FALSE END AS connector_managed
            FROM execution_event e
            LEFT JOIN agent_execution x ON x.id = e.execution_id
            LEFT JOIN agent a ON a.id = x.agent_id
            LEFT JOIN agent p ON p.hermes_profile = x.profile_name
            WHERE e.event_type IN ('TOOL_STARTED', 'TOOL_COMPLETED') AND e.detail IS NOT NULL
            ORDER BY e.execution_id, e.sequence, e.id
            """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<String, String> identifiers = new LinkedHashMap<>();
        long previousExecution = -1;
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery(SELECT_DETAILS);
                PreparedStatement update = connection.prepareStatement("UPDATE execution_event SET detail = ? WHERE id = ?")) {
            while (rows.next()) {
                long executionId = rows.getLong("execution_id");
                if (executionId != previousExecution) {
                    identifiers.clear();
                    previousExecution = executionId;
                }
                String original = rows.getString("detail");
                String redacted = ToolDetailRedactor.redact(original, rows.getBoolean("connector_managed"), identifiers);
                if (!original.equals(redacted)) {
                    update.setString(1, redacted);
                    update.setLong(2, rows.getLong("id"));
                    update.executeUpdate();
                }
            }
        }
    }
}
