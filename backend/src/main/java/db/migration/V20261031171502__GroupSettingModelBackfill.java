package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 그룹의 모델 설정 셋을 {@code group_setting} 의 {@code MODEL} 줄 하나로 옮기고, 옛 단계 코드를 ChatGPT 슬라이더 칸으로 바꾼다.
 *
 * <p>대응은 ADR-20261101 / model-setting 의 이관 표다. {@code FAST}, {@code BALANCED}, {@code DEEP} 은 {@code INSTANT},
 * {@code MEDIUM}, {@code HIGH} 로 가고 {@code EXTRA_HIGH} 와 {@code PRO} 는 빈 칸으로 시작한다. 정의 행도 숨김도 기본
 * 단계도 없던 그룹은 줄을 두지 않는다. 줄이 없으면 모든 칸이 비었다.
 *
 * <p>JSON 은 H2 와 MySQL 에서 같은 SQL 로 만들기 어려워 여기서 만든다. 모양은 Control Plane 의
 * {@code ModelSettingCodec} 이 읽는 버전 1 과 같다. 옮긴 수를 다시 세어 맞지 않으면 실패시켜 옛 표를 지우는 다음
 * 버전으로 넘어가지 않게 한다. 실행 기록의 {@code agent_execution.model_tier} 는 그때 고른 값의 기록이라 옮기지
 * 않는다.
 */
public class V20261031171502__GroupSettingModelBackfill extends BaseJavaMigration {

    private static final String MODEL_KEY = "MODEL";

    private static final int VERSION = 1;

    /** 칸 차례다. Control Plane 의 {@code ModelTier} 선언 순서와 같다. */
    private static final List<String> STOPS = List.of("INSTANT", "MEDIUM", "HIGH", "EXTRA_HIGH", "PRO");

    private static final Map<String, String> RENAMED = Map.of("FAST", "INSTANT", "BALANCED", "MEDIUM", "DEEP", "HIGH");

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, Map<String, String[]>> definitions = readDefinitions(connection);
        Map<Long, String> defaults = readDefaults(connection);
        Map<Long, List<String[]>> hidden = readHidden(connection);

        TreeSet<Long> groups = new TreeSet<>();
        groups.addAll(definitions.keySet());
        defaults.forEach((groupId, tier) -> {
            if (tier != null) {
                groups.add(groupId);
            }
        });
        groups.addAll(hidden.keySet());

        Map<Long, String> values = new LinkedHashMap<>();
        for (Long groupId : groups) {
            values.put(groupId, modelSetting(
                definitions.getOrDefault(groupId, Map.of()),
                defaults.get(groupId),
                hidden.getOrDefault(groupId, List.of())));
        }
        write(connection, defaults, values);
        renameCodes(connection, "app_user", "model_default_tier");
        renameCodes(connection, "conversation", "model_tier");
        renameCodes(connection, "task", "model_tier");
        verify(connection, values);
    }


    /** 그룹마다 옛 단계 코드를 새 칸 코드로 바꾼 정의다. 값은 provider, 모델, effort 차례다. */
    private static Map<Long, Map<String, String[]>> readDefinitions(Connection connection) throws Exception {
        Map<Long, Map<String, String[]>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT group_id, tier, provider, model, reasoning_effort FROM model_tier_definition")) {
            while (rows.next()) {
                String stop = renamed(rows.getString("tier"), "model_tier_definition.tier");
                String[] previous = result.computeIfAbsent(rows.getLong("group_id"), key -> new LinkedHashMap<>())
                    .put(stop, new String[] {
                        rows.getString("provider"), rows.getString("model"), rows.getString("reasoning_effort")});
                if (previous != null) {
                    throw new IllegalStateException("model_tier_definition has duplicate tiers for one group");
                }
            }
        }
        return result;
    }


    /** 그룹마다 새 칸 코드로 바꾼 기본 단계다. 기본 단계가 없는 줄은 null 로 둔다. */
    private static Map<Long, String> readDefaults(Connection connection) throws Exception {
        Map<Long, String> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT group_id, default_tier FROM group_setting")) {
            while (rows.next()) {
                String tier = rows.getString("default_tier");
                result.put(rows.getLong("group_id"), tier == null ? null : renamed(tier, "group_setting.default_tier"));
            }
        }
        return result;
    }


    /** 그룹마다 숨긴 provider 와 모델이다. 모델이 null 이면 그 provider 전체다. */
    private static Map<Long, List<String[]>> readHidden(Connection connection) throws Exception {
        Map<Long, List<String[]>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT group_id, provider, model FROM model_hidden ORDER BY group_id, provider, model, id")) {
            while (rows.next()) {
                String model = rows.getString("model");
                result.computeIfAbsent(rows.getLong("group_id"), key -> new ArrayList<>())
                    .add(new String[] {rows.getString("provider"), model == null || model.isEmpty() ? null : model});
            }
        }
        return result;
    }


    private static String modelSetting(Map<String, String[]> definitions, String defaultTier, List<String[]> hidden) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode tiers = root.putArray("tiers");
        for (String stop : STOPS) {
            String[] mapping = definitions.getOrDefault(stop, new String[3]);
            ObjectNode tier = tiers.addObject();
            tier.put("tier", stop);
            tier.put("provider", mapping[0]);
            tier.put("model", mapping[1]);
            tier.put("reasoningEffort", mapping[2]);
        }
        root.put("defaultTier", defaultTier);
        ArrayNode entries = root.putArray("hidden");
        for (String[] entry : hidden) {
            ObjectNode node = entries.addObject();
            node.put("provider", entry[0]);
            node.put("model", entry[1]);
        }
        return JSON.writeValueAsString(root);
    }


    /** 기본 단계 줄이 있던 그룹은 그 줄을 고치고, 없던 그룹은 줄을 넣는다. 옮길 것이 없는 그룹의 빈 줄은 지운다. */
    private static void write(Connection connection, Map<Long, String> defaults, Map<Long, String> values)
        throws Exception {
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE group_setting SET setting_key = ?, setting_value = ? WHERE group_id = ?");
            PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (?, ?, ?)");
            PreparedStatement delete = connection.prepareStatement("DELETE FROM group_setting WHERE group_id = ?")) {
            for (Map.Entry<Long, String> entry : values.entrySet()) {
                if (defaults.containsKey(entry.getKey())) {
                    update.setString(1, MODEL_KEY);
                    update.setString(2, entry.getValue());
                    update.setLong(3, entry.getKey());
                    requireOne(update.executeUpdate());
                } else {
                    insert.setLong(1, entry.getKey());
                    insert.setString(2, MODEL_KEY);
                    insert.setString(3, entry.getValue());
                    requireOne(insert.executeUpdate());
                }
            }
            for (Long groupId : defaults.keySet()) {
                if (!values.containsKey(groupId)) {
                    delete.setLong(1, groupId);
                    requireOne(delete.executeUpdate());
                }
            }
        }
    }


    private static void renameCodes(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + table + " SET " + column + " = CASE " + column
                + " WHEN 'FAST' THEN 'INSTANT' WHEN 'BALANCED' THEN 'MEDIUM' WHEN 'DEEP' THEN 'HIGH' END"
                + " WHERE " + column + " IN ('FAST', 'BALANCED', 'DEEP')");
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + column
                + " IS NOT NULL AND " + column + " NOT IN ('INSTANT', 'MEDIUM', 'HIGH', 'EXTRA_HIGH', 'PRO')")) {
                rows.next();
                if (rows.getLong(1) != 0) {
                    throw new IllegalStateException(table + "." + column + " has unknown model tier codes");
                }
            }
        }
    }


    /** 옛 표의 수와 옮긴 줄의 수를 대조한다. 다음 버전이 옛 표를 지우므로 여기서 어긋나면 멈춘다. */
    private static void verify(Connection connection, Map<Long, String> values) throws Exception {
        long definitionRows = count(connection, "SELECT COUNT(*) FROM model_tier_definition");
        long hiddenRows = count(connection, "SELECT COUNT(*) FROM model_hidden");
        long defaultRows = count(connection, "SELECT COUNT(*) FROM group_setting WHERE default_tier IS NOT NULL");
        long movedDefinitions = 0;
        long movedHidden = 0;
        long movedDefaults = 0;
        int stored = 0;
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT group_id, setting_key, setting_value FROM group_setting")) {
            while (rows.next()) {
                stored++;
                String value = rows.getString("setting_value");
                if (!MODEL_KEY.equals(rows.getString("setting_key"))
                    || !value.equals(values.get(rows.getLong("group_id")))) {
                    throw new IllegalStateException("group_setting has a row that was not moved");
                }
                var root = JSON.readTree(value);
                for (var tier : root.get("tiers")) {
                    if (!tier.get("provider").isNull() || !tier.get("model").isNull()
                        || !tier.get("reasoningEffort").isNull()) {
                        movedDefinitions++;
                    }
                }
                movedHidden += root.get("hidden").size();
                movedDefaults += root.get("defaultTier").isNull() ? 0 : 1;
            }
        }
        long emptyDefinitions = count(connection, "SELECT COUNT(*) FROM model_tier_definition"
            + " WHERE provider IS NULL AND model IS NULL AND reasoning_effort IS NULL");
        if (stored != values.size()
            || movedDefinitions != definitionRows - emptyDefinitions
            || movedHidden != hiddenRows
            || movedDefaults != defaultRows) {
            throw new IllegalStateException("group_setting backfill counts do not match the old tables");
        }
    }


    private static long count(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }


    private static String renamed(String tier, String source) {
        String stop = RENAMED.get(tier);
        if (stop == null) {
            throw new IllegalStateException(source + " has an unknown model tier code");
        }
        return stop;
    }


    private static void requireOne(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("group_setting backfill changed an unexpected number of rows");
        }
    }
}
