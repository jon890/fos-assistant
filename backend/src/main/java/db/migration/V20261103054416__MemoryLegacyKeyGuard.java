package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 기억의 옛 칸을 지우기 전에 옛 key 로 암호화한 줄이 남았는지 본다(ADR-20261103 / memory-sensitivity-removal).
 *
 * <p>{@code memory.content_key_id}, {@code memory_revision.content_key_id} 가 찬 줄은 환경 변수 key 로 암호화한 글이다.
 * 코드가 그 key 를 읽지 않으므로 칸을 지운 뒤에는 어떤 글이었는지 알 수 없다. 그런 줄이 하나라도 있으면 아무 DDL 도 하지 않고
 * 멈춘다. 이 파일은 읽기만 하므로 멈춰도 칸은 그대로이고, 줄을 데이터 key 로 옮긴 뒤 실패한 이 기록을 정리하고 다시 올리면 된다.
 *
 * <p>SQL 이 아니라 Java 로 쓴 까닭은 마이그레이션 검사가 H2 의 MySQL 모드에서 모든 마이그레이션을 돌리기 때문이다. 조건이 맞으면
 * 실패하는 문장을 H2 와 MySQL 에서 함께 쓸 방법이 SQL 에 없다. 칸을 지우는 일은 뒤의 SQL 마이그레이션 네 개가 표마다 맡는다.
 */
public class V20261103054416__MemoryLegacyKeyGuard extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        long memory = countLegacy(connection, "memory");
        long revision = countLegacy(connection, "memory_revision");
        if (memory + revision > 0) {
            throw new IllegalStateException("옛 key 로 암호화한 기억 줄이 남아 있어 옛 칸을 지우지 않는다. memory " + memory
                + " 줄, memory_revision " + revision + " 줄. 그 줄을 데이터 key 로 옮긴 뒤 실패한 이 마이그레이션 기록을 정리하고"
                + " 다시 배포한다");
        }
    }


    private static long countLegacy(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE content_key_id IS NOT NULL")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
