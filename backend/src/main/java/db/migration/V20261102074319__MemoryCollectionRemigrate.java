package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * 직전 이미지가 {@code memory_collection} 에 쓴 줄을 {@code group_setting} 의 {@code MEMORY_COLLECTIONS} 줄로 다시 옮긴다
 * (ADR-20261102 / table-reduce-r2). 옛 표를 지우는 {@code V20261102074323} 앞 단계다.
 *
 * <p>{@code V20261102074313} 이 한 번 옮긴 뒤에도 직전 이미지가 collection 을 바꿨다면 옛 표에만 있는 그룹이 생긴다.
 * 옛 표의 목록이 코드의 기본 목록과 key, 이름, 순서가 모두 같은 그룹은 줄을 만들지 않는다. 이미 {@code MEMORY_COLLECTIONS}
 * 줄이 있는 그룹은 건너뛴다. 그 줄은 {@code V20261102074313} 이 옮겼거나 새 코드가 쓴 값이고 둘을 구별할 수 없다.
 * 되돌린 동안 직전 이미지가 이미 옮겨진 그룹의 목록을 바꿨다면 옛 표의 그 값은 버려진다. ADR 은 이미 있는 그룹을 덮어쓰지
 * 않기로 했고, 되돌림은 예외 상황이라 값이 다르다고 배포를 멈추지는 않는다. 모양은 Control Plane 의
 * {@code MemoryCollectionService} 가 읽는 버전 1 이다.
 *
 * <p>끝나면 옛 표에서 기본과 다른 모든 그룹이 {@code MEMORY_COLLECTIONS} 줄을 가졌는지 확인하고, 아니면 멈춘다. 지운 뒤에는
 * 복구할 수 없다.
 */
public class V20261102074319__MemoryCollectionRemigrate extends BaseJavaMigration {

    private static final String SETTING_KEY = "MEMORY_COLLECTIONS";

    private static final int VERSION = 1;

    /** {@code V20261102074313} 과 같은 기본 collection 이다. key 와 이름을 순서대로 둔다. */
    private static final List<List<String>> DEFAULTS = List.of(
        List.of("core", "기본"),
        List.of("career", "커리어"),
        List.of("learning", "학습"),
        List.of("health", "건강"),
        List.of("finance", "재무"),
        List.of("home", "집"),
        List.of("identity", "신원"));

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, List<List<String>>> custom = readCustomGroups(connection);
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (?, ?, ?)")) {
            for (Map.Entry<Long, List<List<String>>> entry : custom.entrySet()) {
                if (hasRow(connection, entry.getKey())) {
                    continue;
                }
                insert.setLong(1, entry.getKey());
                insert.setString(2, SETTING_KEY);
                insert.setString(3, setting(entry.getValue()));
                if (insert.executeUpdate() != 1) {
                    throw new IllegalStateException("group_setting MEMORY_COLLECTIONS re-backfill changed an unexpected number of rows");
                }
            }
        }
        for (Long groupId : custom.keySet()) {
            if (!hasRow(connection, groupId)) {
                throw new IllegalStateException("group_setting MEMORY_COLLECTIONS has no row for a group in memory_collection");
            }
        }
    }


    /** 옛 표의 목록이 기본과 다른 그룹과 그 목록이다. 목록은 화면 순서로 읽는다. */
    private static Map<Long, List<List<String>>> readCustomGroups(Connection connection) throws Exception {
        Map<Long, List<List<String>>> groups = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT group_id, collection_key, display_name FROM memory_collection "
                    + "ORDER BY group_id, sort_order, collection_key")) {
            while (rows.next()) {
                groups.computeIfAbsent(rows.getLong("group_id"), key -> new ArrayList<>())
                    .add(List.of(rows.getString("collection_key"), rows.getString("display_name")));
            }
        }
        groups.values().removeIf(DEFAULTS::equals);
        return groups;
    }


    private static boolean hasRow(Connection connection, long groupId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
            "SELECT COUNT(*) FROM group_setting WHERE group_id = ? AND setting_key = '" + SETTING_KEY + "'")) {
            select.setLong(1, groupId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1) > 0;
            }
        }
    }


    private static String setting(List<List<String>> collections) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode array = root.putArray("collections");
        for (List<String> collection : collections) {
            array.addObject().put("key", collection.get(0)).put("name", collection.get(1));
        }
        return JSON.writeValueAsString(root);
    }
}
