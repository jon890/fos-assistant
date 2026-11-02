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
 * 직전 이미지가 {@code content_draft_job} 과 {@code content_draft_asset} 에 쓴 줄을 snapshot 의 job 칸과 revision 의
 * {@code assets_json} 으로 다시 옮긴다(ADR-20261102 / table-reduce-r2 의 원고 D1). 두 옛 테이블을 지우는
 * {@code V20261102074324}, {@code V20261102074325} 와 {@code assets_json} 을 NOT NULL 로 바꾸는 {@code V20261102074321} 앞 단계다.
 *
 * <p>job 은 이미 job 칸이 찬 snapshot(새 코드가 만들었거나 {@code V20261102074317} 이 옮긴 것)을 덮어쓰지 않는다. 같은
 * snapshot 에 새 코드와 직전 이미지가 job 을 따로 만들었다면 두 승인 줄 가운데 어느 쪽이 맞는지 가릴 수 없어, 새 칸을 지키고 옛
 * 테이블의 줄은 버린다. 사진 참조도 {@code assets_json} 이 이미 있는 revision 은 건드리지 않는다. revision 은 만든 뒤 고치지
 * 않아, 값이 없는 줄은 직전 이미지가 만든 것뿐이다. 두 규칙은 되돌림이라는 예외 상황에서 배포를 멈추지 않으려는 선택이다.
 * 사진 참조를 옮기는 모양은 {@code V20261102074317} 과 같다.
 *
 * <p>끝나면 모든 snapshot 이 옛 job 이 있으면 job 칸을, 모든 revision 이 {@code assets_json} 을 갖는지 확인하고, 아니면
 * 멈춘다. 지운 뒤에는 복구할 수 없다.
 */
public class V20261102074320__ContentDraftJobAssetRemigrate extends BaseJavaMigration {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** snapshot 칸과 옛 job 칸의 이름이다. {@code V20261102074317} 과 같다. */
    private static final List<String[]> JOB_COLUMNS = List.of(
        new String[] {"action_id", "action_id"},
        new String[] {"job_id", "job_id"},
        new String[] {"job_status", "status"},
        new String[] {"job_created_at", "created_at"},
        new String[] {"approved_by", "approved_by"},
        new String[] {"approved_at", "approved_at"},
        new String[] {"last_checked_at", "last_checked_at"},
        new String[] {"start_claimed_at", "start_claimed_at"},
        new String[] {"save_claimed_at", "save_claimed_at"},
        new String[] {"job_stage", "stage"},
        new String[] {"save_clicked", "save_clicked"},
        new String[] {"error_code", "error_code"},
        new String[] {"error_reason", "error_reason"});


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        moveJobs(connection);
        moveAssets(connection);
    }


    /** job 칸이 빈 snapshot 에만 옛 job 의 칸을 옮긴다. 시각은 SQL 로 옮겨 시간대 변환을 거치지 않는다. */
    private static void moveJobs(Connection connection) throws Exception {
        var assignments = new ArrayList<String>();
        for (String[] column : JOB_COLUMNS) {
            assignments.add(column[0] + " = (SELECT j." + column[1]
                + " FROM content_draft_job j WHERE j.snapshot_id = content_draft_snapshot.id)");
        }
        String update = "UPDATE content_draft_snapshot SET " + String.join(", ", assignments)
            + " WHERE action_id IS NULL AND EXISTS (SELECT 1 FROM content_draft_job j WHERE j.snapshot_id = content_draft_snapshot.id)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(update);
        }
        long unmoved = count(connection, "SELECT COUNT(*) FROM content_draft_snapshot s JOIN content_draft_job j"
            + " ON j.snapshot_id = s.id WHERE s.action_id IS NULL");
        if (unmoved != 0) {
            throw new IllegalStateException("content_draft_job re-backfill left a snapshot without its job columns");
        }
    }


    /** {@code assets_json} 이 빈 revision 마다 사진 참조를 position 순서의 JSON 배열 하나로 만든다. 사진이 없으면 {@code []} 다. */
    private static void moveAssets(Connection connection) throws Exception {
        Map<Long, ArrayNode> assets = readAssets(connection);
        List<Long> revisionIds = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id FROM content_draft_revision WHERE assets_json IS NULL ORDER BY id")) {
            while (rows.next()) {
                revisionIds.add(rows.getLong(1));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE content_draft_revision SET assets_json = ? WHERE id = ? AND assets_json IS NULL")) {
            for (Long revisionId : revisionIds) {
                ArrayNode list = assets.getOrDefault(revisionId, JSON.createArrayNode());
                update.setString(1, JSON.writeValueAsString(list));
                update.setLong(2, revisionId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("content_draft_revision assets re-backfill changed an unexpected number of rows");
                }
            }
        }
        if (count(connection, "SELECT COUNT(*) FROM content_draft_revision WHERE assets_json IS NULL") != 0) {
            throw new IllegalStateException("content_draft_revision still has rows without assets_json");
        }
    }


    private static Map<Long, ArrayNode> readAssets(Connection connection) throws Exception {
        Map<Long, ArrayNode> result = new TreeMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT revision_id, position, attachment_id, source_fingerprint, observation_id,"
                + " observation_revision FROM content_draft_asset ORDER BY revision_id, position")) {
            while (rows.next()) {
                ObjectNode item = result.computeIfAbsent(rows.getLong("revision_id"), key -> JSON.createArrayNode()).addObject();
                item.put("position", rows.getInt("position"));
                item.put("attachmentId", rows.getLong("attachment_id"));
                item.put("sourceFingerprint", rows.getString("source_fingerprint"));
                long observationId = rows.getLong("observation_id");
                if (rows.wasNull()) {
                    item.putNull("observationId");
                    item.putNull("observationRevision");
                } else {
                    item.put("observationId", observationId);
                    item.put("observationRevision", rows.getLong("observation_revision"));
                }
            }
        }
        return result;
    }


    private static long count(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
