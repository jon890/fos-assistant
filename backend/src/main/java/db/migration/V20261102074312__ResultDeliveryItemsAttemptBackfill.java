package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
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
 * 결과 전달 항목({@code result_delivery_item})을 묶음의 {@code items_json} 으로, 시도({@code result_delivery_attempt})
 * 가운데 마지막 시도를 묶음의 {@code last_attempt_*} 칸으로 옮긴다(ADR-20261102 / table-reduce-r2, ADR-075).
 *
 * <p>JSON 은 H2 와 MySQL 에서 같은 SQL 로 만들기 어려워 여기서 만든다. 모양은 Control Plane 의
 * {@code ResultDeliveryRecorder} 가 읽는 버전 1 과 같고 항목은 옛 표의 {@code id} 순서다. 마지막 시도는
 * {@code attempt_no} 가 가장 큰 시도다. 앞의 시도는 옮기지 않는다. 옛 표는 지우지 않는다. 직전 이미지로 되돌려도 뜨고,
 * 그 이미지가 옛 표에 쓴 줄은 옛 표를 지우는 PR 이 다시 옮긴다.
 *
 * <p>새 칸이 이미 찬 묶음은 건너뛴다. 마지막 시도의 번호가 묶음의 {@code attempt_count} 와 다르거나, 옮긴 수를 옛 표와
 * 다시 세어 맞지 않으면 멈춘다. 코드는 마지막 시도를 {@code attempt_count} 로 가리키므로 두 번호가 같아야 한다.
 */
public class V20261102074312__ResultDeliveryItemsAttemptBackfill extends BaseJavaMigration {

    private static final int ITEMS_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, List<String[]>> items = readItems(connection);
        Map<Long, LastAttempt> attempts = readLastAttempts(connection);
        Map<Long, Integer> attemptCounts = readAttemptCounts(connection);
        for (Map.Entry<Long, LastAttempt> entry : attempts.entrySet()) {
            Integer count = attemptCounts.get(entry.getKey());
            if (count == null || count != entry.getValue().attemptNo()) {
                throw new IllegalStateException(
                    "result_delivery attempt_count does not match the last attempt deliveryId=" + entry.getKey());
            }
        }
        try (PreparedStatement updateItems = connection.prepareStatement(
            "UPDATE result_delivery SET items_json = ? WHERE id = ? AND items_json IS NULL")) {
            for (Map.Entry<Long, List<String[]>> entry : items.entrySet()) {
                updateItems.setString(1, itemsJson(entry.getValue()));
                updateItems.setLong(2, entry.getKey());
                updateItems.executeUpdate();
            }
        }
        try (PreparedStatement updateAttempt = connection.prepareStatement("""
                UPDATE result_delivery
                   SET last_attempt_status = ?, last_attempt_execution_id = ?, last_attempt_notice_message_id = ?,
                       last_attempt_error_code = ?, last_attempt_started_at = ?, last_attempt_finished_at = ?
                 WHERE id = ? AND last_attempt_status IS NULL
                """)) {
            for (Map.Entry<Long, LastAttempt> entry : attempts.entrySet()) {
                LastAttempt attempt = entry.getValue();
                updateAttempt.setString(1, attempt.status());
                setLong(updateAttempt, 2, attempt.executionId());
                setLong(updateAttempt, 3, attempt.noticeMessageId());
                updateAttempt.setString(4, attempt.errorCode());
                updateAttempt.setObject(5, attempt.startedAt());
                updateAttempt.setObject(6, attempt.finishedAt());
                updateAttempt.setLong(7, entry.getKey());
                updateAttempt.executeUpdate();
            }
        }
        verify(connection, items, attempts);
    }


    /** 묶음마다 항목의 {@code (source, result_key)} 다. 옛 표의 {@code id} 순서다. */
    private static Map<Long, List<String[]>> readItems(Connection connection) throws Exception {
        Map<Long, List<String[]>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT delivery_id, source, result_key FROM result_delivery_item ORDER BY delivery_id, id")) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getLong("delivery_id"), key -> new ArrayList<>())
                    .add(new String[] {rows.getString("source"), rows.getString("result_key")});
            }
        }
        return result;
    }


    /** 묶음마다 {@code attempt_no} 가 가장 큰 시도다. */
    private static Map<Long, LastAttempt> readLastAttempts(Connection connection) throws Exception {
        Map<Long, LastAttempt> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("""
                SELECT delivery_id, attempt_no, status, execution_id, notice_message_id, error_code, started_at, finished_at
                  FROM result_delivery_attempt ORDER BY delivery_id, attempt_no
                """)) {
            while (rows.next()) {
                result.put(rows.getLong("delivery_id"), new LastAttempt(
                    rows.getInt("attempt_no"),
                    rows.getString("status"),
                    nullableLong(rows, "execution_id"),
                    nullableLong(rows, "notice_message_id"),
                    rows.getString("error_code"),
                    rows.getObject("started_at", LocalDateTime.class),
                    rows.getObject("finished_at", LocalDateTime.class)));
            }
        }
        return result;
    }


    private static Map<Long, Integer> readAttemptCounts(Connection connection) throws Exception {
        Map<Long, Integer> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id, attempt_count FROM result_delivery")) {
            while (rows.next()) {
                result.put(rows.getLong("id"), rows.getInt("attempt_count"));
            }
        }
        return result;
    }


    private static String itemsJson(List<String[]> items) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", ITEMS_VERSION);
        ArrayNode array = root.putArray("items");
        for (String[] item : items) {
            ObjectNode node = array.addObject();
            node.put("source", item[0]);
            node.put("resultKey", item[1]);
        }
        return JSON.writeValueAsString(root);
    }


    /**
     * 옛 표의 줄 수와 옮긴 수를 대조한다. 옛 표를 지우는 PR 이 이 수를 믿고 지운다.
     *
     * <p>항목은 옮긴 JSON 을 다시 읽어 옛 표의 항목 수와 비교한다. 마지막 시도는 칸이 찬 묶음 수가 시도가 있는 묶음 수와 같은지
     * 본다.
     */
    private static void verify(Connection connection, Map<Long, List<String[]>> items, Map<Long, LastAttempt> attempts)
        throws Exception {
        long oldItems = count(connection, "SELECT COUNT(*) FROM result_delivery_item");
        long movedItems = 0;
        long deliveriesWithItems = 0;
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id, items_json FROM result_delivery WHERE items_json IS NOT NULL")) {
            while (rows.next()) {
                List<String[]> expected = items.get(rows.getLong("id"));
                int size = JSON.readTree(rows.getString("items_json")).get("items").size();
                if (expected == null || size != expected.size()) {
                    throw new IllegalStateException("result_delivery items_json has a row that was not moved");
                }
                movedItems += size;
                deliveriesWithItems++;
            }
        }
        long withAttempt = count(connection, "SELECT COUNT(*) FROM result_delivery WHERE last_attempt_status IS NOT NULL");
        if (movedItems != oldItems || deliveriesWithItems != items.size() || withAttempt != attempts.size()) {
            throw new IllegalStateException("result_delivery backfill counts do not match the old tables");
        }
    }


    private static long count(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }


    private static Long nullableLong(ResultSet rows, String column) throws Exception {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }


    private static void setLong(PreparedStatement statement, int index, Long value) throws Exception {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }


    /** 옛 시도 표의 마지막 시도 한 줄이다. */
    private record LastAttempt(
        int attemptNo,
        String status,
        Long executionId,
        Long noticeMessageId,
        String errorCode,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
    }
}
