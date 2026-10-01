package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code connector_connection.fields} 의 {@code secretPrefixes} 를 모든 행에서 비운다.
 *
 * <p>이전에는 비밀값이 9자 이상이면 앞 8자를 저장했다. 9자에서 16자 사이의 비밀은 절반 이상이 남았는데, 원래 길이를
 * 남기지 않아 그런 행만 골라낼 수 없다. 그래서 앞부분을 줄여 자르지 않고 모두 비운다. 원문은 profile 의 env 에 있어
 * 비워도 연결은 동작하고, 다시 등록하면 새 규칙의 앞부분이 들어간다. {@code values} 와 {@code updated_at} 은 그대로
 * 둔다.
 *
 * <p>SQL 이 아니라 Java 로 쓴 까닭은 마이그레이션 검사가 H2 의 MySQL 모드에서 모든 마이그레이션을 돌리기
 * 때문이다. JSON 텍스트 안의 한 키만 고치는 문장을 H2 와 MySQL 에서 함께 쓸 방법이 SQL 에 없다.
 */
public class V40__ClearConnectorSecretPrefixes extends BaseJavaMigration {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SECRET_PREFIXES = "secretPrefixes";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, String> cleared = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, fields FROM connector_connection")) {
            while (rows.next()) {
                long id = rows.getLong(1);
                ObjectNode root = read(id, rows.getString(2));
                JsonNode prefixes = root.get(SECRET_PREFIXES);
                // 객체가 아닌 값(문자열, 숫자, null)도 앞부분일 수 있어 빈 객체로 바꾼다.
                if (prefixes == null || (prefixes.isObject() && prefixes.isEmpty())) {
                    continue;
                }
                root.putObject(SECRET_PREFIXES);
                cleared.put(id, MAPPER.writeValueAsString(root));
            }
        }
        try (PreparedStatement update =
                connection.prepareStatement("UPDATE connector_connection SET fields = ? WHERE id = ?")) {
            for (Map.Entry<Long, String> row : cleared.entrySet()) {
                update.setString(1, row.getValue());
                update.setLong(2, row.getKey());
                update.executeUpdate();
            }
        }
    }

    /** 열의 원문에는 비밀값의 앞부분이 있으므로 예외에 싣지 않고 원인 예외도 잇지 않는다. */
    private static ObjectNode read(long id, String fields) {
        try {
            if (fields != null && MAPPER.readTree(fields) instanceof ObjectNode root) {
                return root;
            }
        } catch (JacksonException ex) {
            // 아래에서 행 번호만 담아 멈춘다. 원인 예외의 메시지에는 읽던 원문의 일부가 들어간다.
        }
        throw new IllegalStateException(
                "connector_connection 의 id " + id + " 행은 fields 를 JSON 객체로 읽지 못해 secretPrefixes 를 비우지 않는다");
    }
}
