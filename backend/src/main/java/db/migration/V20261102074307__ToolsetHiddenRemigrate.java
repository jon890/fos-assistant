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
 * 직전 이미지가 {@code toolset_hidden} 에 쓴 줄을 {@code group_setting} 의 {@code TOOLSET} 줄로 다시 옮긴다
 * (ADR-20261102 / table-reduce-r2). 옛 표를 지우는 {@code V20261102074311} 앞 단계다.
 *
 * <p>{@code V20261102074304} 가 한 번 옮긴 뒤에도 직전 이미지가 숨김을 바꿨다면 옛 표에만 있는 그룹이 생긴다.
 * 이미 {@code TOOLSET} 줄이 있는 그룹은 건너뛴다. 그 줄은 새 코드가 쓴 최신 값이라 옛 표로 덮어쓰지 않는다.
 * 모양은 Control Plane 의 {@code ToolsetVisibilityService} 가 읽는 버전 1 이다. 이름은 중복 없이 이름 순으로 둔다.
 *
 * <p>끝나면 옛 표에 줄이 있는 모든 그룹이 {@code TOOLSET} 줄을 가졌는지 확인하고, 아니면 멈춘다. 지운 뒤에는 복구할 수 없다.
 */
public class V20261102074307__ToolsetHiddenRemigrate extends BaseJavaMigration {

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
                if (hasToolsetRow(connection, entry.getKey())) {
                    continue;
                }
                insert.setLong(1, entry.getKey());
                insert.setString(2, TOOLSET_KEY);
                insert.setString(3, toolsetSetting(entry.getValue()));
                if (insert.executeUpdate() != 1) {
                    throw new IllegalStateException("group_setting TOOLSET re-backfill changed an unexpected number of rows");
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


    private static boolean hasToolsetRow(Connection connection, long groupId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
            "SELECT COUNT(*) FROM group_setting WHERE group_id = ? AND setting_key = '" + TOOLSET_KEY + "'")) {
            select.setLong(1, groupId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1) > 0;
            }
        }
    }


    private static String toolsetSetting(TreeSet<String> names) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode hidden = root.putArray("hidden");
        names.forEach(hidden::add);
        return JSON.writeValueAsString(root);
    }


    /** 옛 표에 줄이 있는 그룹마다 TOOLSET 줄이 있는지 센다. 핵심은 이 대조다. 뒤 파일이 옛 표를 지운다. */
    private static void verify(Connection connection, Map<Long, TreeSet<String>> hidden) throws Exception {
        for (Long groupId : hidden.keySet()) {
            if (!hasToolsetRow(connection, groupId)) {
                throw new IllegalStateException("group_setting TOOLSET has no row for a group in toolset_hidden");
            }
        }
    }
}
