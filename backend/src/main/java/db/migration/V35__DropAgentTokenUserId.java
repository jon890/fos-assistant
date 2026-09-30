package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * {@code agent_token.user_id} 칸을 지운다. 토큰은 profile 만 증명하고 사용자를 갖지 않는다(ADR-032).
 *
 * <p>칸을 지우기 전에 profile 이 비고 폐기되지 않은 줄을 센다. 그런 줄이 하나라도 있으면 아무 DDL 도 하지 않고
 * 실패한다. 칸을 먼저 지우면 그 토큰이 누구의 것이었는지 잃은 뒤에야 알게 되므로, 잃기 전에 배포를 멈춘다.
 * profile 이 비었어도 이미 폐기된 옛 줄은 이력으로 남긴다.
 *
 * <p>SQL 이 아니라 Java 로 쓴 까닭은 마이그레이션 검사가 H2 의 MySQL 모드에서 모든 마이그레이션을 돌리기
 * 때문이다. 조건이 맞으면 실패하는 문장을 H2 와 MySQL 에서 함께 쓸 방법이 SQL 에 없다.
 */
public class V35__DropAgentTokenUserId extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        long unbound;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM agent_token WHERE profile_name IS NULL AND revoked_at IS NULL")) {
            rows.next();
            unbound = rows.getLong(1);
        }
        if (unbound > 0) {
            throw new IllegalStateException("profile 이 묶이지 않은 폐기 안 된 MCP 토큰이 " + unbound
                    + " 개 있어 agent_token.user_id 를 지우지 않는다. 그 토큰을 폐기하거나 이전 판에서 profile 을 묶고,"
                    + " 실패한 V35 기록을 정리한 뒤 다시 배포한다");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE agent_token DROP COLUMN user_id");
        }
    }
}
