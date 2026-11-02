package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 처음 옮기는 마이그레이션이다. 이 버전까지는 옛 테이블이 남아 있다. */
    private static final String MOVED = "20261102053011";


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

        migrate(database, MOVED);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(questionOf(statement, 1)).isEqualTo(500L);
            assertThat(questionOf(statement, 2)).isNull();
            try (ResultSet count = statement.executeQuery("select count(*) from execution_question")) {
                count.next();
                assertThat(count.getLong(1)).as("처음 옮긴 단계는 옛 테이블을 지우지 않는다").isEqualTo(2L);
            }
        }
    }


    @Test
    @DisplayName("직전 이미지가 쓴 질문 줄은 다시 옮기고 정리한 대화의 실행은 질문 번호를 비운 뒤 옛 테이블을 지운다")
    void remigratesQuestionsWrittenByPreviousImageAndDropsOldTable() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE_QUESTION_COLUMN);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertConversation(statement, 1, false);
            insertConversation(statement, 2, true);
            insertExecution(statement, 1, 1);
            insertExecution(statement, 2, 2);
            statement.executeUpdate("insert into execution_question (execution_id,message_id,created_at)"
                + " values (1,500,CURRENT_TIMESTAMP),(2,501,CURRENT_TIMESTAMP)");
        }
        migrate(database, MOVED);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            // 직전 이미지로 되돌린 동안 옛 코드가 한 일이다. 3번 실행의 질문은 옛 테이블에만 있고,
            // 4번 실행의 대화는 정리됐으며, 5번 실행의 질문은 새 코드가 이미 채운 값과 다르다.
            insertConversation(statement, 3, false);
            insertExecution(statement, 3, 3);
            insertExecution(statement, 4, 2);
            insertExecution(statement, 5, 3);
            statement.executeUpdate("update agent_execution set question_message_id = 600 where id = 5");
            statement.executeUpdate("insert into execution_question (execution_id,message_id,created_at)"
                + " values (3,502,CURRENT_TIMESTAMP),(4,503,CURRENT_TIMESTAMP),(5,504,CURRENT_TIMESTAMP)");
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(questionOf(statement, 1)).isEqualTo(500L);
            assertThat(questionOf(statement, 2)).as("정리한 대화의 실행은 앞서 옮긴 값도 비운다").isNull();
            assertThat(questionOf(statement, 3)).as("옛 테이블에만 있던 질문을 옮긴다").isEqualTo(502L);
            assertThat(questionOf(statement, 4)).as("정리한 대화의 실행은 옮기지 않는다").isNull();
            assertThat(questionOf(statement, 5)).as("이미 값이 있는 실행은 덮어쓰지 않는다").isEqualTo(600L);
            assertThatThrownBy(() -> statement.executeQuery("select count(*) from execution_question"))
                .as("옛 테이블은 지운다").isInstanceOf(SQLException.class);
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


    private static void insertExecution(Statement statement, long id, long conversationId) throws SQLException {
        insertExecution(statement, id);
        statement.executeUpdate("update agent_execution set conversation_id=" + conversationId + " where id=" + id);
    }


    private static void insertConversation(Statement statement, long id, boolean purged) throws SQLException {
        statement.executeUpdate("insert into conversation (id,public_id,user_id,title,created_at,updated_at,purged_at) values ("
            + id + ",X'" + String.format("%032x", id) + "',1,'검사',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
            + (purged ? "CURRENT_TIMESTAMP" : "NULL") + ")");
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
