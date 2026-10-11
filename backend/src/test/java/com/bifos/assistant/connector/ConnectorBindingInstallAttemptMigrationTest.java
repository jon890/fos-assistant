package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기존 바인딩의 상태를 그대로 두고 nullable BINARY(16) 시도 칸만 더하는지 H2와 MySQL에서 본다. */
class ConnectorBindingInstallAttemptMigrationTest extends ConnectorBindingMigrationTest {
    @Test
    @DisplayName("직전 Flyway 스키마의 바인딩 상태를 보존하고 새 시도는 null이다")
    void addsNullableAttemptWithoutChangingExistingState() throws SQLException {
        migrate("20261010043702");
        List<String> before = states();
        migrate("20261011040236");
        assertThat(states()).isEqualTo(before);
        try (Connection connection = connect();
            Statement query = connection.createStatement();
            ResultSet row = query.executeQuery("SELECT COUNT(*) FROM agent_connector_binding WHERE install_attempt_id IS NOT NULL")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt(1)).isZero();
        }
    }


    @Test
    @DisplayName("새 UUID를 16바이트로 저장하고 소비한 뒤 null로 비울 수 있다")
    void storesAndClearsBinaryUuid() throws SQLException {
        migrate("20261011040236");
        UUID id = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        try (Connection connection = connect();
            PreparedStatement update = connection.prepareStatement("UPDATE agent_connector_binding SET install_attempt_id = ?")) {
            update.setBytes(1, bytes);
            assertThat(update.executeUpdate()).isEqualTo(2);
            try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT install_attempt_id FROM agent_connector_binding")) {
                while (rows.next()) {
                    assertThat(rows.getBytes(1)).isEqualTo(bytes);
                }
            }
            update.setBytes(1, null);
            assertThat(update.executeUpdate()).isEqualTo(2);
        }
    }


    private List<String> states() throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect();
            Statement query = connection.createStatement();
            ResultSet rows = query.executeQuery("SELECT id, status, desired_enabled, restart_required FROM agent_connector_binding ORDER BY id")) {
            while (rows.next()) {
                values.add(rows.getLong(1) + ":" + rows.getString(2) + ":" + rows.getBoolean(3) + ":" + rows.getBoolean(4));
            }
        }
        return values;
    }
}
