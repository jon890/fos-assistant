package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 정렬 규칙이 다른 두 표의 문자열 칸을 비교하는 쿼리를 MySQL 이 줄이 없어도 오류 1267 로 거절한다는 것을 단언한다.
 *
 * <p>{@link RepositoryQueryMysqlTest} 는 빈 표에서 모든 저장소 쿼리를 한 번씩 실행해 이 종류의 결함을 잡는다고 기대한다. 이 검사는 그 기대의
 * 전제, 곧 빈 표에서도 MySQL 이 쿼리를 거절한다는 것을 보인다. 이 검사가 실패하면 저장소 쿼리 검사가 정렬 규칙 섞임을 더는 잡지 못한다는 뜻이다.
 *
 * <p>예전에는 앱 전체를 정렬 규칙을 통일하기 전 스키마(V57)로 띄워 같은 것을 보였다. 그 방식은 V57 뒤에 생긴 기동 작업마다 막아야 해서
 * 마이그레이션이 더해질 때마다 고쳐야 했다. 지금은 표 두 개만 만들어 앱과 마이그레이션에 기대지 않는다.
 */
@Tag("mysql")
class CollationMixQueryMysqlTest {

    private static final int ILLEGAL_MIX_OF_COLLATIONS = 1267;

    /** 운영 서버의 기본 정렬 규칙이다. scripts/checks/check-mysql-migration.sh 가 서버를 이 값으로 띄운다. */
    private static final String SERVER_COLLATION = "utf8mb4_unicode_ci";

    /** 정렬 규칙을 적지 않은 MySQL 8.4 의 기본값이다. 예전 스키마의 표 일부가 이 값이었다. */
    private static final String DEFAULT_COLLATION = "utf8mb4_0900_ai_ci";

    private MysqlTestDatabase database;


    @BeforeEach
    void createTables() throws SQLException {
        database = MysqlTestDatabase.create();
        execute("CREATE TABLE parent_row (id BIGINT PRIMARY KEY, public_id CHAR(36) NOT NULL) COLLATE " + SERVER_COLLATION);
        execute("CREATE TABLE child_row (id BIGINT PRIMARY KEY, parent_public_id CHAR(36) NOT NULL) COLLATE " + DEFAULT_COLLATION);
        execute("CREATE TABLE same_child_row (id BIGINT PRIMARY KEY, parent_public_id CHAR(36) NOT NULL) COLLATE " + SERVER_COLLATION);
    }


    @Test
    @DisplayName("정렬 규칙이 다른 칸을 비교하는 조인과 하위 쿼리는 빈 표에서도 오류 1267 로 실패한다")
    void rejectsCollationMixOnEmptyTables() {
        assertThatThrownBy(() -> query(
            "SELECT COUNT(*) FROM parent_row p JOIN child_row c ON c.parent_public_id = p.public_id"))
            .isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ILLEGAL_MIX_OF_COLLATIONS));
        assertThatThrownBy(() -> query(
            "SELECT COUNT(*) FROM child_row c WHERE c.parent_public_id NOT IN (SELECT p.public_id FROM parent_row p)"))
            .isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ILLEGAL_MIX_OF_COLLATIONS));
    }


    @Test
    @DisplayName("정렬 규칙이 같은 칸의 비교는 통과한다")
    void allowsSameCollation() throws SQLException {
        assertThat(query("SELECT COUNT(*) FROM parent_row p JOIN same_child_row c ON c.parent_public_id = p.public_id")).isZero();
    }


    private long query(String sql) throws SQLException {
        try (Connection connection = connect();
            Statement statement = connection.createStatement();
            var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }


    private void execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }


    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }
}
