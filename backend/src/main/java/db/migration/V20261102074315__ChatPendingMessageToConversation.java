package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 대기 메시지({@code chat_pending_message})를 대화마다 {@code conversation.pending_messages} JSON 하나로 옮긴다
 * (ADR-20261102 / table-reduce-r2, ADR-048).
 *
 * <p>JSON 은 H2 와 MySQL 에서 같은 SQL 로 만들기 어려워 여기서 만든다. 모양은 Control Plane 의
 * {@code PendingMessageStore} 가 읽는 버전 1 과 같다. 글은 옛 {@code id} 순서로 두고 번호도 그대로 쓴다. 한 행이라도 멈춰
 * 있었으면 줄 전체가 멈춘다. 옛 코드도 그렇게 읽었다.
 *
 * <p>옮긴 행은 옛 테이블에서 지운다. 남기면 직전 이미지로 되돌렸을 때 새 코드가 이미 보낸 글을 다시 보내고, 옛 테이블을 지우는
 * PR 이 직전 이미지가 쓴 행을 다시 옮길 때 같은 글이 둘이 된다. 테이블은 지우지 않는다. 대화 줄이 없는 행은 보낼 곳이 없어
 * 옮기지 않고 남긴다.
 *
 * <p>대화 줄에 이미 대기 줄이 있으면 덮어쓰지 않고 멈춘다. 이 칸은 이 이관이 처음 쓰므로 있을 수 없는 상태다. 옮긴 수와 지운
 * 수가 맞지 않아도 멈춘다.
 */
public class V20261102074315__ChatPendingMessageToConversation extends BaseJavaMigration {

    private static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, List<Row>> byConversation = readRows(connection);
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE conversation SET pending_messages = ? WHERE id = ? AND pending_messages IS NULL");
            PreparedStatement exists = connection.prepareStatement("SELECT COUNT(*) FROM conversation WHERE id = ?");
            PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM chat_pending_message WHERE conversation_id = ?")) {
            for (Map.Entry<Long, List<Row>> entry : byConversation.entrySet()) {
                Long conversationId = entry.getKey();
                update.setString(1, pendingMessages(entry.getValue()));
                update.setLong(2, conversationId);
                if (update.executeUpdate() != 1) {
                    if (count(exists, conversationId) != 0) {
                        throw new IllegalStateException("conversation already has pending messages: " + conversationId);
                    }
                    continue;
                }
                delete.setLong(1, conversationId);
                if (delete.executeUpdate() != entry.getValue().size()) {
                    throw new IllegalStateException("chat_pending_message rows changed while moving: " + conversationId);
                }
            }
        }
    }


    /** 대화마다 옛 {@code id} 순서로 읽는다. */
    private static Map<Long, List<Row>> readRows(Connection connection) throws Exception {
        Map<Long, List<Row>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT id, conversation_id, content, held, created_at FROM chat_pending_message"
                    + " ORDER BY conversation_id, id")) {
            while (rows.next()) {
                // 애플리케이션은 시각을 UTC 로 저장한다(hibernate.jdbc.time_zone).
                LocalDateTime createdAt = rows.getObject("created_at", LocalDateTime.class);
                Row row = new Row(
                    rows.getLong("id"),
                    rows.getString("content"),
                    rows.getBoolean("held"),
                    createdAt.toInstant(ZoneOffset.UTC).toString());
                result.computeIfAbsent(rows.getLong("conversation_id"), key -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }


    private static String pendingMessages(List<Row> rows) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("held", rows.stream().anyMatch(Row::held));
        ArrayNode items = root.putArray("items");
        for (Row row : rows) {
            ObjectNode item = items.addObject();
            item.put("id", row.id());
            item.put("content", row.content());
            item.put("createdAt", row.createdAt());
        }
        return JSON.writeValueAsString(root);
    }


    private static long count(PreparedStatement statement, Long id) throws Exception {
        statement.setLong(1, id);
        try (ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }


    private record Row(long id, String content, boolean held, String createdAt) {
    }
}
