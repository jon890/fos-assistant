package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * 이미 남긴 살펴보기 보고({@code proactive_check.report_json})에 되풀이 판정 재료를 채운다. 그 살펴보기가 옛 표에 남긴 「새로 알릴 것」
 * 발견(주제 키가 있는 것)을 {@code announced} 에, 받아들인 문제 후보의 문제 키를 {@code acceptedProblemKeys} 에 쓴다
 * ({@code docs/features/proactive/design.md}).
 *
 * <p>이 마이그레이션 뒤로 Control Plane 은 되풀이 판정을 보고에서 읽고 옛 표를 읽지 않는다. 채우지 않으면 배포 직후 첫 살펴보기가 지난
 * 기간에 이미 알린 소식을 다시 알린다. 칸 모양은 Control Plane 의 {@code CheckReport} 와 {@code ReportedFinding} 이 읽는 것과 같다.
 *
 * <p>JSON 은 H2 와 MySQL 에서 같은 SQL 로 다루기 어려워 여기서 만든다. H2 는 글을 JSON 으로 읽게 {@code FORMAT JSON} 을 붙여야
 * 한다. 두 칸이 이미 있는 보고는 건너뛰므로 다시 돌려도 같다. 옛 표는 지우지 않는다.
 */
public class V20261105040320__ProactiveReportAnnouncedBackfill extends BaseJavaMigration {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ANNOUNCED = "announced";

    private static final String ACCEPTED_PROBLEM_KEYS = "acceptedProblemKeys";


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean mysql = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        Map<Long, String> reports = readReports(connection);
        Map<Long, List<ObjectNode>> findings = readFindings(connection);
        Map<Long, Set<String>> problemKeys = readProblemKeys(connection);
        String update = mysql
            ? "UPDATE proactive_check SET report_json = ? WHERE id = ?"
            : "UPDATE proactive_check SET report_json = ? FORMAT JSON WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            for (Map.Entry<Long, String> entry : reports.entrySet()) {
                JsonNode parsed = JSON.readTree(entry.getValue());
                if (!(parsed instanceof ObjectNode report) || (report.has(ANNOUNCED) && report.has(ACCEPTED_PROBLEM_KEYS))) {
                    continue;
                }
                ArrayNode announced = report.putArray(ANNOUNCED);
                findings.getOrDefault(entry.getKey(), List.of()).forEach(announced::add);
                ArrayNode accepted = report.putArray(ACCEPTED_PROBLEM_KEYS);
                problemKeys.getOrDefault(entry.getKey(), Set.of()).forEach(accepted::add);
                statement.setString(1, JSON.writeValueAsString(report));
                statement.setLong(2, entry.getKey());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("proactive_check report backfill changed an unexpected number of rows");
                }
            }
        }
    }


    /** 보고가 있는 살펴보기의 보고 JSON 글이다. */
    private static Map<Long, String> readReports(Connection connection) throws Exception {
        Map<Long, String> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT id, report_json FROM proactive_check WHERE report_json IS NOT NULL")) {
            while (rows.next()) {
                result.put(rows.getLong(1), rows.getString(2));
            }
        }
        return result;
    }


    /** 살펴보기마다 「새로 알릴 것」 발견 가운데 주제 키가 있는 것을 번호 순서로 낸다. */
    private static Map<Long, List<ObjectNode>> readFindings(Connection connection) throws Exception {
        Map<Long, List<ObjectNode>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("""
                    SELECT check_id, topic_key, source_url, title FROM proactive_check_finding
                     WHERE kind = 'NEW' AND topic_key IS NOT NULL ORDER BY id
                    """)) {
            while (rows.next()) {
                String topicKey = rows.getString(2);
                if (topicKey.isBlank()) {
                    continue;
                }
                ObjectNode finding = JSON.createObjectNode();
                finding.put("topicKey", topicKey);
                finding.put("sourceUrl", rows.getString(3));
                finding.put("title", rows.getString(4));
                result.computeIfAbsent(rows.getLong(1), ignored -> new ArrayList<>()).add(finding);
            }
        }
        return result;
    }


    /** 살펴보기마다 받아들인 문제 후보의 문제 키다. 옛 표가 이미 정규화한 값을 갖는다. 같은 키는 한 번만 둔다. */
    private static Map<Long, Set<String>> readProblemKeys(Connection connection) throws Exception {
        Map<Long, Set<String>> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT check_id, problem_key FROM proactive_check_problem WHERE status = 'ACCEPTED' ORDER BY id")) {
            while (rows.next()) {
                String key = rows.getString(2);
                if (key == null || key.isBlank()) {
                    continue;
                }
                result.computeIfAbsent(rows.getLong(1), ignored -> new LinkedHashSet<>()).add(key);
            }
        }
        return result;
    }
}
