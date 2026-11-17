package com.bifos.assistant.testsupport;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Hermes 내장 위임 하위 에이전트의 등록 줄을 끝 소식 칸까지 채워 넣는다(ADR-20261117 / native-delegation-wake).
 *
 * <p>글 칸은 평문이고 key 칸은 비운다. 등록과 끝 소식 경로 자체는 {@code SubagentSessionEndpointTest} 가 본다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NativeResultFixtures {

    /**
     * 등록 줄 하나를 넣고 번호를 돌려준다.
     *
     * @param stoppedAt 끝 소식을 받은 시각. 비우면 아직 소식이 없다
     * @param status {@code SUCCEEDED}, {@code FAILED}. 소식이 없으면 null
     */
    public static long binding(
        JdbcTemplate jdbc,
        String profileName,
        Long userId,
        Long originExecutionId,
        boolean wakeEligible,
        Instant createdAt,
        Instant stoppedAt,
        String status,
        String resultText,
        String goal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO hermes_session_binding (profile_name, session_id, user_id, origin_execution_id, root_session_id,"
                    + " parent_session_id, created_at, wake_eligible, goal_preview, stopped_at, result_status, result_text)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, profileName);
            statement.setString(2, "child-" + UUID.randomUUID());
            statement.setLong(3, userId);
            statement.setLong(4, originExecutionId);
            statement.setString(5, "root-" + originExecutionId);
            statement.setString(6, "root-" + originExecutionId);
            statement.setTimestamp(7, Timestamp.from(createdAt));
            statement.setBoolean(8, wakeEligible);
            statement.setString(9, goal);
            statement.setTimestamp(10, stoppedAt == null ? null : Timestamp.from(stoppedAt));
            statement.setString(11, status);
            statement.setString(12, resultText);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }


    /** 그 profile 의 등록 줄을 지운다. */
    public static void clear(JdbcTemplate jdbc, String profileName) {
        jdbc.update("DELETE FROM hermes_session_binding WHERE profile_name = ?", profileName);
    }
}
