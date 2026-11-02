package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 상시 허락({@code connector_tool_grant})과 대화 허락({@code connector_conversation_grant})을 허락을 준
 * {@code connector_action} 줄의 {@code grant_*} 칸으로 옮긴다(ADR-20261102 / table-reduce-r2).
 *
 * <p>대화 허락은 {@code action_id} 로 그 줄을 안다. 그 줄의 사용자, 대화, 플러그인, 도구가 허락과 같을 때만 옮긴다. 에이전트는 같거나,
 * 지운 에이전트를 정리해 승인 줄 쪽이 비었을 때만 옮긴다. 빈 에이전트의 허락은 어느 실행에도 통하지 않는다.
 *
 * <p>상시 허락은 승인 줄 번호를 갖지 않는다. 승인이 같은 트랜잭션에서 허락 줄을 넣고 승인 시각({@code decided_at})을 적으므로, 같은
 * 사용자와 플러그인, 도구의 승인 줄 가운데 허락을 아직 갖지 않았고 승인 시각이 허락의 {@code created_at} 앞뒤 1분 안인 줄에서 가장
 * 가까운 줄을 고른다.
 *
 * <p>고를 줄이 없는 허락은 옮기지 않는다. 그 도구는 다시 승인을 받는다. 허락이 넓어지는 쪽으로는 옮기지 않는다.
 *
 * <p>옮긴 줄은 옛 표에서 지운다. 남기면 직전 이미지로 되돌렸을 때 새 코드가 거둔 허락이 옛 표에서 다시 통한다. 옮기지 못한 줄은
 * 그대로 둔다. 표는 지우지 않는다.
 */
public class V20261102074327__ConnectorGrantToAction extends BaseJavaMigration {

    /** 상시 허락의 생성 시각과 승인 시각이 이만큼 안에 있어야 같은 승인으로 본다. */
    static final Duration MATCH_WINDOW = Duration.ofMinutes(1);


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        moveConversationGrants(connection);
        moveToolGrants(connection);
    }


    private static void moveConversationGrants(Connection connection) throws Exception {
        List<ConversationGrant> grants = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("""
                SELECT id, user_id, conversation_id, agent_id, connector_id, tool_name, action_id, expires_at, revoked_at
                  FROM connector_conversation_grant ORDER BY id
                """)) {
            while (rows.next()) {
                grants.add(new ConversationGrant(
                    rows.getLong("id"),
                    rows.getLong("user_id"),
                    rows.getLong("conversation_id"),
                    rows.getLong("agent_id"),
                    rows.getString("connector_id"),
                    rows.getString("tool_name"),
                    rows.getLong("action_id"),
                    rows.getObject("expires_at", LocalDateTime.class),
                    rows.getObject("revoked_at", LocalDateTime.class)));
            }
        }
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE connector_action
               SET grant_scope = 'CONVERSATION', grant_expires_at = ?, grant_revoked_at = ?
             WHERE id = ? AND grant_scope IS NULL AND user_id = ? AND conversation_id = ?
               AND (agent_id = ? OR agent_id IS NULL) AND connector_id = ? AND tool_name = ?
            """);
            PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM connector_conversation_grant WHERE id = ?")) {
            for (ConversationGrant grant : grants) {
                update.setObject(1, grant.expiresAt());
                update.setObject(2, grant.revokedAt());
                update.setLong(3, grant.actionId());
                update.setLong(4, grant.userId());
                update.setLong(5, grant.conversationId());
                update.setLong(6, grant.agentId());
                update.setString(7, grant.connectorId());
                update.setString(8, grant.toolName());
                if (update.executeUpdate() == 1) {
                    deleteRow(delete, grant.id());
                }
            }
        }
    }


    private static void moveToolGrants(Connection connection) throws Exception {
        List<ToolGrant> grants = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("""
                SELECT id, user_id, connector_id, tool_name, expires_at, created_at, revoked_at
                  FROM connector_tool_grant ORDER BY id
                """)) {
            while (rows.next()) {
                grants.add(new ToolGrant(
                    rows.getLong("id"),
                    rows.getLong("user_id"),
                    rows.getString("connector_id"),
                    rows.getString("tool_name"),
                    rows.getObject("expires_at", LocalDateTime.class),
                    rows.getObject("created_at", LocalDateTime.class),
                    rows.getObject("revoked_at", LocalDateTime.class)));
            }
        }
        try (PreparedStatement candidates = connection.prepareStatement("""
            SELECT id, decided_at FROM connector_action
             WHERE user_id = ? AND connector_id = ? AND tool_name = ? AND grant_scope IS NULL
               AND decided_at IS NOT NULL AND decided_at >= ? AND decided_at <= ?
             ORDER BY id
            """);
            PreparedStatement update = connection.prepareStatement("""
                UPDATE connector_action SET grant_scope = 'TOOL', grant_expires_at = ?, grant_revoked_at = ?
                 WHERE id = ? AND grant_scope IS NULL
                """);
            PreparedStatement delete = connection.prepareStatement("DELETE FROM connector_tool_grant WHERE id = ?")) {
            for (ToolGrant grant : grants) {
                Long actionId = closestAction(candidates, grant);
                if (actionId == null) {
                    continue;
                }
                update.setObject(1, grant.expiresAt());
                update.setObject(2, grant.revokedAt());
                update.setLong(3, actionId);
                if (update.executeUpdate() == 1) {
                    deleteRow(delete, grant.id());
                }
            }
        }
    }


    /** 승인 시각이 허락의 생성 시각에 가장 가까운 승인 줄이다. 같으면 번호가 작은 줄이다. 없으면 null 이다. */
    private static Long closestAction(PreparedStatement candidates, ToolGrant grant) throws Exception {
        candidates.setLong(1, grant.userId());
        candidates.setString(2, grant.connectorId());
        candidates.setString(3, grant.toolName());
        candidates.setObject(4, grant.createdAt().minus(MATCH_WINDOW));
        candidates.setObject(5, grant.createdAt().plus(MATCH_WINDOW));
        Long closest = null;
        Duration best = null;
        try (ResultSet rows = candidates.executeQuery()) {
            while (rows.next()) {
                Duration gap = Duration.between(rows.getObject("decided_at", LocalDateTime.class), grant.createdAt()).abs();
                if (best == null || gap.compareTo(best) < 0) {
                    best = gap;
                    closest = rows.getLong("id");
                }
            }
        }
        return closest;
    }


    private static void deleteRow(PreparedStatement delete, long id) throws Exception {
        delete.setLong(1, id);
        if (delete.executeUpdate() != 1) {
            throw new IllegalStateException("grant row changed while moving: " + id);
        }
    }


    private record ConversationGrant(
        long id, long userId, long conversationId, long agentId, String connectorId, String toolName, long actionId,
        LocalDateTime expiresAt, LocalDateTime revokedAt) {
    }

    private record ToolGrant(
        long id, long userId, String connectorId, String toolName, LocalDateTime expiresAt, LocalDateTime createdAt,
        LocalDateTime revokedAt) {
    }
}
