package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 직전 이미지가 옛 허락 테이블({@code connector_tool_grant}, {@code connector_conversation_grant})에 새로 쓴 줄을 허락을 준
 * {@code connector_action} 줄의 {@code grant_*} 칸으로 다시 옮긴다(ADR-20261102 / table-reduce-r2). 옛 테이블을 지우는
 * {@code V20261103024543} 부터 앞 단계다.
 *
 * <p>옮기는 규칙은 처음 옮긴 {@code V20261102074327} 과 같다. 다른 점은 {@code created_at} 이 그 마이그레이션이 시작한 시각 뒤인
 * 줄만 옮긴다는 것이다. 이미 옮긴 줄을 다시 옮기면, 새 코드가 거둔 상시 허락이 다른 승인 줄로 되살아난다. 그 시작 시각은 적용
 * 기록의 적용 시각에서 실행 시간을 뺀 값이다. 적용 기록은 서버의 시각대로 적히고 허락 줄은 UTC 로 적힌다. 서버가 UTC 보다 앞선
 * 시각대면 시작 시각이 늦게 잡혀 옮기지 못하는 쪽으로만 틀리고, 옮기지 못한 허락은 다시 승인받는다.
 *
 * <p>고를 승인 줄이 없거나 이미 허락을 가진 줄만 있으면 옮기지 않는다. 허락이 넓어지는 쪽으로는 옮기지 않는다.
 */
public class V20261103024542__ConnectorGrantRemigrate extends BaseJavaMigration {

    /** 상시 허락의 생성 시각과 승인 시각이 이만큼 안에 있어야 같은 승인으로 본다. */
    static final Duration MATCH_WINDOW = Duration.ofMinutes(1);

    /** 처음 옮긴 마이그레이션의 버전이다. Flyway 적용 기록에는 접두 {@code V} 없이 적힌다. */
    private static final String FIRST_MOVE_VERSION = "20261102074327";


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        LocalDateTime cutoff = firstMoveStartedAt(context);
        moveConversationGrants(connection, cutoff);
        moveToolGrants(connection, cutoff);
    }


    /**
     * 처음 옮긴 마이그레이션이 시작한 시각이다. 적용 기록이 없으면 어디까지가 이미 옮긴 줄인지 몰라 멈춘다. H2 는 적용 기록의 이름과
     * 칸을 소문자로 따옴표를 쳐 만들어서 DB 가 알려 주는 따옴표로 감싼다.
     */
    private static LocalDateTime firstMoveStartedAt(Context context) throws Exception {
        Connection connection = context.getConnection();
        String quote = connection.getMetaData().getIdentifierQuoteString();
        String sql = "SELECT %1$sinstalled_on%1$s, %1$sexecution_time%1$s FROM %1$s%2$s%1$s WHERE %1$sversion%1$s = ? AND %1$ssuccess%1$s = ?"
            .formatted(quote, context.getConfiguration().getTable());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, FIRST_MOVE_VERSION);
            statement.setBoolean(2, true);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("no flyway history for " + FIRST_MOVE_VERSION);
                }
                return rows.getObject(1, LocalDateTime.class).minus(Duration.ofMillis(rows.getLong(2)));
            }
        }
    }


    private static void moveConversationGrants(Connection connection, LocalDateTime cutoff) throws Exception {
        List<ConversationGrant> grants = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
            SELECT id, user_id, conversation_id, agent_id, connector_id, tool_name, action_id, expires_at, revoked_at
              FROM connector_conversation_grant WHERE created_at > ? ORDER BY id
            """)) {
            select.setObject(1, cutoff);
            try (ResultSet rows = select.executeQuery()) {
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
        }
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE connector_action
               SET grant_scope = 'CONVERSATION', grant_expires_at = ?, grant_revoked_at = ?
             WHERE id = ? AND grant_scope IS NULL AND user_id = ? AND conversation_id = ?
               AND (agent_id = ? OR agent_id IS NULL) AND connector_id = ? AND tool_name = ?
            """)) {
            for (ConversationGrant grant : grants) {
                update.setObject(1, grant.expiresAt());
                update.setObject(2, grant.revokedAt());
                update.setLong(3, grant.actionId());
                update.setLong(4, grant.userId());
                update.setLong(5, grant.conversationId());
                update.setLong(6, grant.agentId());
                update.setString(7, grant.connectorId());
                update.setString(8, grant.toolName());
                update.executeUpdate();
            }
        }
    }


    private static void moveToolGrants(Connection connection, LocalDateTime cutoff) throws Exception {
        List<ToolGrant> grants = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
            SELECT id, user_id, connector_id, tool_name, expires_at, created_at, revoked_at
              FROM connector_tool_grant WHERE created_at > ? ORDER BY id
            """)) {
            select.setObject(1, cutoff);
            try (ResultSet rows = select.executeQuery()) {
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
                """)) {
            for (ToolGrant grant : grants) {
                Long actionId = closestAction(candidates, grant);
                if (actionId == null) {
                    continue;
                }
                update.setObject(1, grant.expiresAt());
                update.setObject(2, grant.revokedAt());
                update.setLong(3, actionId);
                update.executeUpdate();
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


    private record ConversationGrant(
        long id, long userId, long conversationId, long agentId, String connectorId, String toolName, long actionId,
        LocalDateTime expiresAt, LocalDateTime revokedAt) {
    }

    private record ToolGrant(
        long id, long userId, String connectorId, String toolName, LocalDateTime expiresAt, LocalDateTime createdAt,
        LocalDateTime revokedAt) {
    }
}
