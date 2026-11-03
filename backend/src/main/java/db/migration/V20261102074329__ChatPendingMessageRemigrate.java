package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 직전 이미지가 {@code chat_pending_message} 에 쓴 행을 대화의 {@code conversation.pending_messages} 로 다시 옮긴다
 * (ADR-20261102 / table-reduce-r2). 옛 테이블을 지우는 {@code V20261102074333} 앞 단계다.
 *
 * <p>대화 줄에 이미 대기 줄이 있으면 덮어쓰지 않고 그 뒤에 옛 행을 옛 {@code id} 순서로 이어 붙인다. 처음 옮길 때와 달리
 * 멈추지 않는다. 되돌렸다 다시 올린 대화는 새 코드가 쓴 글과 직전 이미지가 쓴 글이 함께 있을 수 있고, 둘 다 사용자가 아직
 * 보내지 않은 글이어서 하나도 버리지 않는다. 옛 {@code id} 가 이미 쓰인 번호와 겹치면 가장 큰 번호 다음 번호를 받는다.
 * 멈춤({@code held})은 어느 한쪽이라도 멈췄으면 멈춘다.
 *
 * <p>옮긴 행은 옛 테이블에서 지우고, 지운 수가 옮긴 수와 다르면 멈춘다. 대화 줄이 없는 행은 보낼 곳이 없어 옮기지 않고
 * 테이블과 함께 버린다. 이미 있는 대기 줄을 읽지 못하면 이어 붙일 수 없어 멈춘다.
 */
public class V20261102074329__ChatPendingMessageRemigrate extends BaseJavaMigration {

    private static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, List<Row>> byConversation = readRows(connection);
        try (PreparedStatement read = connection.prepareStatement("SELECT pending_messages FROM conversation WHERE id = ?");
            PreparedStatement update = connection.prepareStatement("UPDATE conversation SET pending_messages = ? WHERE id = ?");
            PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM chat_pending_message WHERE conversation_id = ?")) {
            for (Map.Entry<Long, List<Row>> entry : byConversation.entrySet()) {
                Long conversationId = entry.getKey();
                read.setLong(1, conversationId);
                String existing;
                try (ResultSet rows = read.executeQuery()) {
                    if (!rows.next()) {
                        continue;
                    }
                    existing = rows.getString(1);
                }
                update.setString(1, merge(existing, entry.getValue(), conversationId));
                update.setLong(2, conversationId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("conversation changed while moving pending messages: " + conversationId);
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


    /** 이미 있는 대기 줄 뒤에 옛 행을 잇는다. 칸이 비어 있으면 옛 행만으로 만든다. */
    private static String merge(String existing, List<Row> rows, Long conversationId) {
        ObjectNode root;
        if (existing == null) {
            root = JSON.createObjectNode();
            root.put("version", VERSION);
            root.put("held", false);
            root.putArray("items");
        } else {
            JsonNode parsed = JSON.readTree(existing);
            if (!(parsed instanceof ObjectNode object) || parsed.path("version").asInt() != VERSION
                || !parsed.path("items").isArray()) {
                throw new IllegalStateException("conversation has unreadable pending messages: " + conversationId);
            }
            root = object;
        }
        ArrayNode items = (ArrayNode)root.get("items");
        Set<Long> used = new HashSet<>();
        long max = 0;
        for (JsonNode item : items) {
            used.add(item.path("id").asLong());
            max = Math.max(max, item.path("id").asLong());
        }
        boolean held = root.path("held").asBoolean(false);
        for (Row row : rows) {
            long id = row.id();
            if (used.contains(id)) {
                id = max + 1;
            }
            used.add(id);
            max = Math.max(max, id);
            ObjectNode item = items.addObject();
            item.put("id", id);
            item.put("content", row.content());
            item.put("createdAt", row.createdAt());
            held |= row.held();
        }
        root.put("held", held);
        return JSON.writeValueAsString(root);
    }


    private record Row(long id, String content, boolean held, String createdAt) {
    }
}
