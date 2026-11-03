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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 직전 이미지가 {@code result_delivery_item} 과 {@code result_delivery_attempt} 에 쓴 줄을 묶음의 {@code items_json} 과
 * {@code last_attempt_*} 칸으로 다시 옮긴다(ADR-20261102 / table-reduce-r2 의 결과 전달 D3). 두 옛 테이블을 지우는
 * {@code V20261102074331}, {@code V20261102074332} 와 세 칸을 NOT NULL 로 바꾸는 {@code V20261102074330} 앞 단계다.
 *
 * <p>모양과 옮기는 규칙은 {@code V20261102074312} 와 같다. 달라지는 것은 이미 칸이 찬 묶음을 대하는 방식이다. 항목은
 * {@code items_json} 이 이미 있는 묶음을, 마지막 시도는 {@code last_attempt_status} 가 이미 있는 묶음을 덮어쓰지 않는다. 새
 * 코드가 만들었거나 처음 옮길 때 채운 값이어서, 되돌린 동안 직전 이미지가 옛 표에 쓴 시도가 있어도 새 칸을 지키고 옛 줄은
 * 버린다. 되돌림이라는 예외 상황이라 값이 다르다고 배포를 멈추지 않는다.
 *
 * <p>칸이 빈 묶음의 마지막 시도 번호가 묶음의 {@code attempt_count} 와 다르면 멈춘다. 코드는 마지막 시도를
 * {@code attempt_count} 로 가리키므로 두 번호가 같아야 한다. 끝나면 옛 표에 항목과 시도가 있는 묶음이 모두 칸을 갖는지
 * 확인하고, 아니면 멈춘다. 지운 뒤에는 복구할 수 없다.
 */
public class V20261102074328__ResultDeliveryRemigrate extends BaseJavaMigration {

    private static final int ITEMS_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Set<Long> needItems = idsWhere(connection, "items_json IS NULL");
        Set<Long> needAttempt = idsWhere(connection, "last_attempt_status IS NULL");
        Map<Long, List<String[]>> items = readItems(connection);
        Map<Long, LastAttempt> attempts = readLastAttempts(connection);
        Map<Long, Integer> attemptCounts = readAttemptCounts(connection);
        for (Map.Entry<Long, LastAttempt> entry : attempts.entrySet()) {
            if (!needAttempt.contains(entry.getKey())) {
                continue;
            }
            Integer count = attemptCounts.get(entry.getKey());
            if (count == null || count != entry.getValue().attemptNo()) {
                throw new IllegalStateException(
                    "result_delivery attempt_count does not match the last attempt deliveryId=" + entry.getKey());
            }
        }
        try (PreparedStatement updateItems = connection.prepareStatement(
            "UPDATE result_delivery SET items_json = ? WHERE id = ? AND items_json IS NULL")) {
            for (Map.Entry<Long, List<String[]>> entry : items.entrySet()) {
                if (needItems.contains(entry.getKey())) {
                    updateItems.setString(1, itemsJson(entry.getValue()));
                    updateItems.setLong(2, entry.getKey());
                    updateItems.executeUpdate();
                }
            }
        }
        try (PreparedStatement updateAttempt = connection.prepareStatement("""
                UPDATE result_delivery
                   SET last_attempt_status = ?, last_attempt_execution_id = ?, last_attempt_notice_message_id = ?,
                       last_attempt_error_code = ?, last_attempt_started_at = ?, last_attempt_finished_at = ?
                 WHERE id = ? AND last_attempt_status IS NULL
                """)) {
            for (Map.Entry<Long, LastAttempt> entry : attempts.entrySet()) {
                if (!needAttempt.contains(entry.getKey())) {
                    continue;
                }
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
        verify(connection, items.keySet(), attempts.keySet());
    }


    /** 그 조건에 맞는 묶음의 번호다. 옮기기 전에 읽어, 이 마이그레이션이 채워야 할 묶음을 가른다. */
    private static Set<Long> idsWhere(Connection connection, String condition) throws Exception {
        Set<Long> result = new TreeSet<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id FROM result_delivery WHERE " + condition)) {
            while (rows.next()) {
                result.add(rows.getLong(1));
            }
        }
        return result;
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
     * 옛 표에 항목이나 시도가 있는 묶음이 모두 새 칸을 갖는지 본다. 옛 표를 지우는 파일이 이 결과를 믿고 지운다.
     *
     * <p>묶음 줄이 없어 옮길 곳이 없는 옛 줄은 외래 키가 막으므로 있을 수 없다.
     */
    private static void verify(Connection connection, Set<Long> itemDeliveries, Set<Long> attemptDeliveries)
        throws Exception {
        long missingItems = 0;
        long missingAttempts = 0;
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT id, items_json, last_attempt_status FROM result_delivery")) {
            while (rows.next()) {
                long id = rows.getLong("id");
                if (itemDeliveries.contains(id) && rows.getString("items_json") == null) {
                    missingItems++;
                }
                if (attemptDeliveries.contains(id) && rows.getString("last_attempt_status") == null) {
                    missingAttempts++;
                }
            }
        }
        if (missingItems != 0 || missingAttempts != 0) {
            throw new IllegalStateException("result_delivery re-backfill left deliveries without items or last attempt");
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
