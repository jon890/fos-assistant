package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code execution_question} 의 질문 메시지 번호를 {@code agent_execution.question_message_id} 로 옮기는 마이그레이션을 본다. */
class ExecutionQuestionMigrationTest {
    /** 질문 컬럼을 더하는 마이그레이션 바로 앞 버전이다. 그 사이 버전은 out-of-order 로 함께 적용된다. */
    private static final String BEFORE_QUESTION_COLUMN = "20261102053007";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database("jdbc:h2:mem:question-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("옛 질문 줄의 메시지를 실행 줄로 옮기고 질문 없는 실행은 비워 두며 옛 테이블은 남긴다")
    void movesQuestionMessageIntoExecution() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE_QUESTION_COLUMN);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertExecution(statement, 1);
            insertExecution(statement, 2);
            statement.executeUpdate("insert into execution_question (execution_id,message_id,created_at)"
                + " values (1,500,CURRENT_TIMESTAMP),(99,501,CURRENT_TIMESTAMP)");
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(questionOf(statement, 1)).isEqualTo(500L);
            assertThat(questionOf(statement, 2)).isNull();
            try (ResultSet count = statement.executeQuery("select count(*) from execution_question")) {
                count.next();
                assertThat(count.getLong(1)).as("옛 테이블은 지우지 않는다").isEqualTo(2L);
            }
        }
    }


    private static void migrate(Database database, String target) {
        var configuration = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .outOfOrder(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    private static void insertExecution(Statement statement, long id) throws SQLException {
        statement.executeUpdate("insert into agent_execution (id,user_id,profile_name,cost_mode,status,started_at,"
            + "event_observation) values (" + id + ",1,'p','SUBSCRIPTION','SUCCEEDED',CURRENT_TIMESTAMP,'UNKNOWN')");
    }


    private static Long questionOf(Statement statement, long id) throws SQLException {
        try (ResultSet rows = statement.executeQuery("select question_message_id from agent_execution where id=" + id)) {
            rows.next();
            long value = rows.getLong(1);
            return rows.wasNull() ? null : value;
        }
    }


    record Database(String url, String username, String password) {
    }
}
