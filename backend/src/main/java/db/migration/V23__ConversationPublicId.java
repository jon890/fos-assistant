package db.migration;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 대화 주소와 API 에 1씩 늘어나는 번호 대신 맞힐 수 없는 공개 식별자를 쓰려고 {@code public_id} 칸을 더한다.
 * PK 는 그대로다.
 *
 * <p>새 대화는 애플리케이션이 v7 을 넣는다. 이미 있는 대화는 여기서 v4 임의 값을 채운다. MySQL 에 v7 함수가
 * 없고, {@code UUID()} 는 v1 이라 DB 호스트의 MAC 주소와 시각이 들어간다.
 *
 * <p>SQL 이 아니라 Java 로 쓴 까닭은 마이그레이션 검사가 H2 의 MySQL 모드에서 모든 마이그레이션을 돌리기
 * 때문이다. 임의 바이트를 만드는 함수와 16진수를 바이트로 바꾸는 함수가 MySQL 과 H2 에 함께 있는 것이 없다.
 * {@link UUID#randomUUID()} 는 {@code SecureRandom} 으로 v4 를 만든다.
 */
public class V23__ConversationPublicId extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE conversation ADD COLUMN public_id BINARY(16) NULL");
        }

        List<Long> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM conversation WHERE public_id IS NULL")) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        }
        try (PreparedStatement update =
                connection.prepareStatement("UPDATE conversation SET public_id = ? WHERE id = ?")) {
            for (Long id : ids) {
                update.setBytes(1, bytesOf(UUID.randomUUID()));
                update.setLong(2, id);
                update.addBatch();
            }
            if (!ids.isEmpty()) {
                update.executeBatch();
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE conversation MODIFY public_id BINARY(16) NOT NULL");
            statement.execute("ALTER TABLE conversation ADD UNIQUE KEY uk_conversation_public_id (public_id)");
        }
    }

    /** Hibernate 가 {@code BINARY} 칸에 UUID 를 두는 순서와 같게 앞 8바이트, 뒤 8바이트로 둔다. */
    private static byte[] bytesOf(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
