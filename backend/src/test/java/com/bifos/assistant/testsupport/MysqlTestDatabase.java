package com.bifos.assistant.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 실제 MySQL 서버에 빈 데이터베이스를 하나 만들어 그 접속 값을 준다.
 *
 * <p>`mysql` 태그가 붙은 검사만 쓴다. 서버는 `scripts/check-mysql-migration.sh` 가 운영과 같은 기본 정렬 규칙으로 띄우고
 * 접속 값을 환경 변수로 넘긴다. 데이터베이스는 정렬 규칙을 적지 않고 만들어 서버 기본값을 물려받게 한다.
 */
public record MysqlTestDatabase(String url, String username, String password) {

    private static final String SERVER_URL = "MIGRATION_MYSQL_URL";
    private static final String USERNAME = "MIGRATION_MYSQL_USERNAME";
    private static final String PASSWORD = "MIGRATION_MYSQL_PASSWORD";

    public static MysqlTestDatabase create() {
        String serverUrl = required(SERVER_URL);
        String username = required(USERNAME);
        String password = required(PASSWORD);
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL 에 검사용 데이터베이스를 만들지 못했다", e);
        }
        String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        return new MysqlTestDatabase(base + name, username, password);
    }

    /** 값이 없으면 건너뛰지 않고 실패한다. 건너뛰면 MySQL 검사가 돌지 않은 채 통과로 보인다. */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 이 없다. 이 검사는 scripts/check-mysql-migration.sh 로 돌린다");
        }
        return value;
    }
}
