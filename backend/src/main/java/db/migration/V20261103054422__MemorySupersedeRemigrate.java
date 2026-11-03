package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 직전 이미지가 옛 연결 테이블({@code memory_link})에 새로 맺은 효력 있는 대체 연결({@code SUPERSEDES}, {@code ACTIVE})을 옛 항목의
 * {@code superseded_by_memory_id} 로 다시 옮긴다(ADR-20261102 / table-reduce-r2). 옛 테이블을 지우는 {@code V20261103054423} 부터 앞 단계다.
 *
 * <p>옮기는 규칙은 처음 옮긴 {@code V20261103054415} 와 같다. 한 항목을 가리키는 효력 있는 줄이 여럿이면 번호가 큰 줄의 새 항목을 쓰고,
 * 제안 중이거나 끊은 줄과 {@code RELATED} 는 옮기지 않는다. 다른 점은 {@code created_at} 이 그 마이그레이션이 시작한 시각 뒤인 줄만 옮기고,
 * 칸이 이미 찬 항목은 덮지 않는다는 것이다. 이미 옮긴 칸을 다시 덮으면 새 코드가 비운 항목이 옛 줄 때문에 다시 가려진다. 시작 시각은 적용 기록의
 * 적용 시각에서 실행 시간을 뺀 값이다. 적용 기록은 서버의 시각대로 적히고 줄은 UTC 로 적힌다. 서버가 UTC 보다 앞선 시각대면 시작 시각이 늦게 잡혀
 * 옮기지 못하는 쪽으로만 틀리고, 옮기지 못한 대체는 항목이 다시 실린다. 서버가 UTC 보다 뒤진 시각대면 반대로 이미 옮긴 줄을 다시 옮길 수 있다.
 * 운영 서버는 UTC 이상의 시각대라 이 경우를 다루지 않는다.
 *
 * <p>시작 시각 앞에 맺은 줄은 이미 옮겼다. 그 줄을 다시 옮기면 새 코드가 대신한 새 항목이 승인을 벗어나서 비운 칸이 옛 줄 때문에 다시 차고, 되살아난
 * 옛 항목이 다시 가려진다.
 */
public class V20261103054422__MemorySupersedeRemigrate extends BaseJavaMigration {

    /** 처음 옮긴 마이그레이션의 버전이다. Flyway 적용 기록에는 접두 {@code V} 없이 적힌다. */
    private static final String FIRST_MOVE_VERSION = "20261103054415";


    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        LocalDateTime cutoff = firstMoveStartedAt(context);
        // 한 옛 항목에 효력 있는 줄이 여럿이면 번호 순으로 읽어 마지막(번호가 큰) 줄의 새 항목이 남게 한다
        Map<Long, Long> newerByOlder = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement("""
            SELECT from_memory_id, to_memory_id FROM memory_link
             WHERE type = 'SUPERSEDES' AND status = 'ACTIVE' AND created_at > ? ORDER BY id
            """)) {
            select.setObject(1, cutoff);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    newerByOlder.put(rows.getLong("to_memory_id"), rows.getLong("from_memory_id"));
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE memory SET superseded_by_memory_id = ? WHERE id = ? AND superseded_by_memory_id IS NULL
            """)) {
            for (Map.Entry<Long, Long> entry : newerByOlder.entrySet()) {
                update.setLong(1, entry.getValue());
                update.setLong(2, entry.getKey());
                update.executeUpdate();
            }
        }
    }


    /**
     * 처음 옮긴 마이그레이션이 시작한 시각이다. 적용 기록이 없으면 어디까지가 이미 옮긴 줄인지 몰라 멈춘다. H2 는 적용 기록의 이름과
     * 칸을 소문자로 따옴표를 쳐 만들어서 DB 가 알려 주는 따옴표로 감싼다.
     */
    private static LocalDateTime firstMoveStartedAt(Context context) throws Exception {
        Connection connection = context.getConnection();
        String quote = connection.getMetaData().getIdentifierQuoteString();
        String sql = "SELECT %1$sinstalled_on%1$s, %1$sexecution_time%1$s FROM %1$s%2$s%1$s WHERE %1$sversion%1$s = ? AND %1$ssuccess%1$s = ?"
            .formatted(quote, context.getConfiguration().getTable());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, FIRST_MOVE_VERSION);
            statement.setBoolean(2, true);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("no flyway history for " + FIRST_MOVE_VERSION);
                }
                return rows.getObject(1, LocalDateTime.class).minus(Duration.ofMillis(rows.getLong(2)));
            }
        }
    }
}
