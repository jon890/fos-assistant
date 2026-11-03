package com.bifos.assistant.connector;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 테스트에서 허락을 준 승인 줄을 바로 넣고 읽는다. 허락은 그 허락을 준 {@code connector_action} 줄의 {@code grant_*} 칸이다
 * (ADR-20261102 / table-reduce-r2).
 *
 * <p>승인 흐름을 거치지 않고 허락만 있는 상태를 만들 때 쓴다. 넣는 줄은 끝난 승인 줄(`SUCCEEDED`)이다.
 */
final class GrantRows {

    private final JdbcTemplate jdbc;


    GrantRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    /** 상시 허락을 준 승인 줄을 넣고 번호를 돌려준다. */
    long tool(Long userId, String connectorId, String toolName, Instant expiresAt) {
        return insert("TOOL", userId, null, null, connectorId, toolName, expiresAt);
    }


    /** 대화 허락을 준 승인 줄을 넣고 번호를 돌려준다. */
    long conversation(
        Long userId, Long conversationId, Long agentId, String connectorId, String toolName, Instant expiresAt) {
        return insert("CONVERSATION", userId, conversationId, agentId, connectorId, toolName, expiresAt);
    }


    void revoke(long id, Instant at) {
        jdbc.update("UPDATE connector_action SET grant_revoked_at = ? WHERE id = ?", Timestamp.from(at), id);
    }


    /** 상시 허락을 준 줄들이다. 번호 순서다. */
    List<Row> tools() {
        return rows("TOOL");
    }


    /** 대화 허락을 준 줄들이다. 번호 순서다. */
    List<Row> conversations() {
        return rows("CONVERSATION");
    }


    private List<Row> rows(String scope) {
        return jdbc.query("""
                SELECT id, user_id, conversation_id, agent_id, connector_id, tool_name, grant_expires_at, grant_revoked_at
                  FROM connector_action WHERE grant_scope = ? ORDER BY id
                """,
            (rs, rowNum) -> new Row(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getObject("conversation_id", Long.class),
                rs.getObject("agent_id", Long.class),
                rs.getString("connector_id"),
                rs.getString("tool_name"),
                instant(rs.getTimestamp("grant_expires_at")),
                instant(rs.getTimestamp("grant_revoked_at"))),
            scope);
    }


    private long insert(
        String scope, Long userId, Long conversationId, Long agentId, String connectorId, String toolName,
        Instant expiresAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,
                        approval_mode, decision, passed, check_tree, status, origin_execution_id, conversation_id, dedupe_key,
                        args_sha256, created_at, grant_scope, grant_expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'WRITE', 'REQUIRED', 'NEEDS_APPROVAL', FALSE, FALSE, 'SUCCEEDED', 0, ?, ?, ?, ?,
                        ?, ?)
                    """, new String[] {"id"});
            UUID publicId = UUID.randomUUID();
            statement.setBytes(1, ByteBuffer.allocate(16)
                .putLong(publicId.getMostSignificantBits())
                .putLong(publicId.getLeastSignificantBits())
                .array());
            statement.setLong(2, userId);
            statement.setObject(3, agentId);
            statement.setString(4, connectorId);
            statement.setString(5, toolName);
            statement.setString(6, connectorId + "_" + toolName);
            statement.setObject(7, conversationId);
            statement.setString(8, "grant-" + publicId);
            statement.setString(9, "0".repeat(64));
            statement.setTimestamp(10, Timestamp.from(Instant.now()));
            statement.setString(11, scope);
            statement.setTimestamp(12, Timestamp.from(expiresAt));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }


    /** 테스트 DB 의 시각 칸은 JPA 가 쓰는 것과 같은 {@link Timestamp} 로 넣고 읽는다. */
    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }


    /** 허락을 준 승인 줄 하나다. 칸 이름은 옛 허락 엔티티의 접근자와 같다. */
    record Row(
        Long id, Long userId, Long conversationId, Long agentId, String connectorId, String toolName, Instant expiresAt,
        Instant revokedAt) {
    }
}
