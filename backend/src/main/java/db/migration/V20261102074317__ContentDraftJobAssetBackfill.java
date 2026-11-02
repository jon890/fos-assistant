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
 * 원고 저장 job({@code content_draft_job})을 그 snapshot 줄의 칸으로, 사진 참조({@code content_draft_asset})를 revision 의
 * {@code assets_json} 으로 옮긴다(ADR-20261102 / table-reduce-r2 의 원고 D1).
 *
 * <p>job 은 snapshot 과 1:1 이라({@code uk_content_job_snapshot}) 칸 값을 SQL 로 그대로 옮긴다. 시각은 JDBC 로 꺼내지 않아
 * 시간대 변환을 거치지 않는다. 사진 참조의 JSON 은 H2 와 MySQL 에서 같은 SQL 로 만들기 어려워 여기서 만든다. 모양은
 * Control Plane 의 {@code ContentDraftAssetsConverter} 가 읽는 배열과 같고 position 순서다. 사진이 없는 revision 은
 * {@code []} 를 받는다.
 *
 * <p>옛 테이블은 지우지 않는다. 직전 이미지로 되돌려도 뜨고, 그 이미지가 옛 테이블에 쓴 줄은 옛 테이블을 지우는 PR 이
 * 다시 옮긴다. 옮긴 수를 옛 테이블과 다시 세어 맞지 않으면 멈춘다.
 */
public class V20261102074317__ContentDraftJobAssetBackfill extends BaseJavaMigration {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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


    /** job 마다 그 snapshot 줄 하나에 칸을 옮긴다. 이미 job 칸이 찬 snapshot 은 건드리지 않는다. */
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
        long oldRows = count(connection, "SELECT COUNT(*) FROM content_draft_job");
        long movedRows = count(connection, "SELECT COUNT(*) FROM content_draft_snapshot s JOIN content_draft_job j"
            + " ON j.snapshot_id = s.id AND j.action_id = s.action_id AND j.status = s.job_status");
        if (oldRows != movedRows) {
            throw new IllegalStateException("content_draft_job backfill counts do not match content_draft_snapshot");
        }
    }


    /** revision 마다 사진 참조를 position 순서의 JSON 배열 하나로 만든다. 이미 값이 있는 revision 은 건드리지 않는다. */
    private static void moveAssets(Connection connection) throws Exception {
        Map<Long, ArrayNode> assets = readAssets(connection);
        long movedAssets = 0;
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT id FROM content_draft_revision WHERE assets_json IS NULL ORDER BY id");
            PreparedStatement update = connection.prepareStatement(
                "UPDATE content_draft_revision SET assets_json = ? WHERE id = ? AND assets_json IS NULL")) {
            List<Long> revisionIds = new ArrayList<>();
            while (rows.next()) {
                revisionIds.add(rows.getLong(1));
            }
            for (Long revisionId : revisionIds) {
                ArrayNode list = assets.getOrDefault(revisionId, JSON.createArrayNode());
                update.setString(1, JSON.writeValueAsString(list));
                update.setLong(2, revisionId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("content_draft_revision assets backfill changed an unexpected number of rows");
                }
                movedAssets += list.size();
            }
        }
        long assetRows = count(connection, "SELECT COUNT(*) FROM content_draft_asset");
        long emptyRevisions = count(connection, "SELECT COUNT(*) FROM content_draft_revision WHERE assets_json IS NULL");
        if (movedAssets != assetRows || emptyRevisions != 0) {
            throw new IllegalStateException("content_draft_asset backfill counts do not match content_draft_revision");
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
