package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 그룹이 도구 화면에서 숨긴 toolset({@code toolset_hidden})을 {@code group_setting} 의 {@code TOOLSET} 줄 하나로 옮긴다
 * (ADR-20261102 / table-reduce-r2).
 *
 * <p>JSON 은 H2 와 MySQL 에서 같은 SQL 로 만들기 어려워 여기서 만든다. 모양은 Control Plane 의
 * {@code ToolsetVisibilityService} 가 읽는 버전 1 과 같다. 이름은 중복 없이 이름 순으로 둔다. 옛 표는 지우지 않는다.
 * 직전 이미지로 되돌려도 뜨고, 그 이미지가 옛 표에 쓴 줄은 옛 표를 지우는 PR 이 다시 옮긴다.
 *
 * <p>같은 그룹의 {@code TOOLSET} 줄이 이미 있으면 덮어쓰지 않고 멈춘다. 이 키는 이 이관이 처음 쓰므로 있을 수 없는
 * 상태다. 옮긴 수를 옛 표와 다시 세어 맞지 않아도 멈춘다.
 */
public class V20261102074304__ToolsetHiddenToGroupSetting extends BaseJavaMigration {

    private static final String TOOLSET_KEY = "TOOLSET";

    private static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, TreeSet<String>> hidden = readHidden(connection);
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (?, ?, ?)")) {
            for (Map.Entry<Long, TreeSet<String>> entry : hidden.entrySet()) {
                insert.setLong(1, entry.getKey());
                insert.setString(2, TOOLSET_KEY);
                insert.setString(3, toolsetSetting(entry.getValue()));
                if (insert.executeUpdate() != 1) {
                    throw new IllegalStateException("group_setting TOOLSET backfill changed an unexpected number of rows");
                }
            }
        }
        verify(connection, hidden);
    }


    /** 그룹마다 숨긴 toolset 이름이다. 같은 이름은 하나로 합치고 이름 순으로 정렬한다. */
    private static Map<Long, TreeSet<String>> readHidden(Connection connection) throws Exception {
        Map<Long, TreeSet<String>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT group_id, name FROM toolset_hidden")) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getLong("group_id"), key -> new TreeSet<>()).add(rows.getString("name"));
            }
        }
        return result;
    }


    private static String toolsetSetting(TreeSet<String> names) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode hidden = root.putArray("hidden");
        names.forEach(hidden::add);
        return JSON.writeValueAsString(root);
    }


    /** 옛 표의 줄 수와 옮긴 줄의 수를 대조한다. 옛 표를 지우는 PR 이 이 수를 믿고 지운다. */
    private static void verify(Connection connection, Map<Long, TreeSet<String>> hidden) throws Exception {
        long oldRows = count(connection, "SELECT COUNT(*) FROM toolset_hidden");
        long storedRows = count(connection, "SELECT COUNT(*) FROM group_setting WHERE setting_key = '" + TOOLSET_KEY + "'");
        long movedNames = 0;
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT group_id, setting_value FROM group_setting WHERE setting_key = '" + TOOLSET_KEY + "'")) {
            while (rows.next()) {
                TreeSet<String> expected = hidden.get(rows.getLong("group_id"));
                var root = JSON.readTree(rows.getString("setting_value"));
                if (expected == null || root.get("hidden").size() != expected.size()) {
                    throw new IllegalStateException("group_setting TOOLSET has a row that was not moved");
                }
                movedNames += root.get("hidden").size();
            }
        }
        if (storedRows != hidden.size() || movedNames != oldRows) {
            throw new IllegalStateException("group_setting TOOLSET backfill counts do not match toolset_hidden");
        }
    }


    private static long count(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
