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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 그룹이 쓰는 Memory collection({@code memory_collection})을 {@code group_setting} 의 {@code MEMORY_COLLECTIONS} 줄로
 * 옮긴다(ADR-20261102 / table-reduce-r2).
 *
 * <p>JSON 은 H2 와 MySQL 에서 같은 SQL 로 만들기 어려워 여기서 만든다. 모양은 Control Plane 의
 * {@code MemoryCollectionService} 가 읽는 버전 1 과 같다. 목록 순서는 옛 표의 {@code sort_order} 순이다.
 *
 * <p>코드의 기본 목록과 key 와 이름, 순서가 모두 같은 그룹은 줄을 만들지 않는다. 줄이 없으면 코드가 기본 목록을 내므로
 * 같은 결과이고, 기본 목록을 바꿀 때 그룹마다 고칠 줄이 생기지 않는다. 기본 목록은 이 이관이 실행되는 시점의 값을 여기에
 * 고정해 둔다. 옛 표는 지우지 않는다. 직전 이미지로 되돌려도 뜨고, 그 이미지가 옛 표에 쓴 줄은 옛 표를 지우는 PR 이 다시
 * 옮긴다.
 *
 * <p>같은 그룹의 {@code MEMORY_COLLECTIONS} 줄이 이미 있으면 덮어쓰지 않고 멈춘다. 이 키는 이 이관이 처음 쓰므로 있을 수
 * 없는 상태다. 옮긴 줄을 옛 표와 다시 대조해 맞지 않아도 멈춘다.
 */
public class V20261102074313__MemoryCollectionToGroupSetting extends BaseJavaMigration {

    private static final String SETTING_KEY = "MEMORY_COLLECTIONS";

    private static final int VERSION = 1;

    /** 이관 시점의 기본 collection 이다. key 와 이름을 순서대로 둔다. */
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
        Map<Long, List<List<String>>> groups = readGroups(connection);
        Map<Long, List<List<String>>> custom = new TreeMap<>();
        groups.forEach((groupId, collections) -> {
            if (!collections.equals(DEFAULTS)) {
                custom.put(groupId, collections);
            }
        });
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (?, ?, ?)")) {
            for (Map.Entry<Long, List<List<String>>> entry : custom.entrySet()) {
                insert.setLong(1, entry.getKey());
                insert.setString(2, SETTING_KEY);
                insert.setString(3, setting(entry.getValue()));
                if (insert.executeUpdate() != 1) {
                    throw new IllegalStateException("group_setting MEMORY_COLLECTIONS backfill changed an unexpected number of rows");
                }
            }
        }
        verify(connection, custom);
    }


    /** 그룹마다 옛 표의 collection 을 {@code [key, 이름]} 으로 화면 순서대로 읽는다. */
    private static Map<Long, List<List<String>>> readGroups(Connection connection) throws Exception {
        Map<Long, List<List<String>>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT group_id, collection_key, display_name FROM memory_collection "
                    + "ORDER BY group_id, sort_order, collection_key")) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getLong("group_id"), key -> new ArrayList<>())
                    .add(List.of(rows.getString("collection_key"), rows.getString("display_name")));
            }
        }
        return result;
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


    /**
     * 옛 표와 옮긴 줄을 다시 대조한다. 옮긴 줄은 옛 표에서 기본과 다른 그룹과 정확히 같은 그룹이고, 목록도 같아야 한다. 옛 표를
     * 지우는 PR 이 이 대조를 믿고 지운다.
     */
    private static void verify(Connection connection, Map<Long, List<List<String>>> custom) throws Exception {
        long storedRows = 0;
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT group_id, setting_value FROM group_setting WHERE setting_key = '" + SETTING_KEY + "'")) {
            while (rows.next()) {
                storedRows++;
                List<List<String>> expected = custom.get(rows.getLong("group_id"));
                if (expected == null || !read(rows.getString("setting_value")).equals(expected)) {
                    throw new IllegalStateException("group_setting MEMORY_COLLECTIONS has a row that was not moved");
                }
            }
        }
        if (storedRows != custom.size()) {
            throw new IllegalStateException("group_setting MEMORY_COLLECTIONS backfill counts do not match memory_collection");
        }
    }


    private static List<List<String>> read(String value) {
        List<List<String>> result = new ArrayList<>();
        for (JsonNode node : JSON.readTree(value).get("collections")) {
            result.add(List.of(node.get("key").asString(), node.get("name").asString()));
        }
        return result;
    }
}
