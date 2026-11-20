package com.bifos.assistant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.crypto.application.DataKeyRewrapper;
import com.bifos.assistant.crypto.config.DataEncryptionProperties;
import com.bifos.assistant.crypto.domain.entity.ServerSecret;
import com.bifos.assistant.crypto.infra.DatabaseServerSecrets;
import com.bifos.assistant.crypto.infra.FileKeyEncryptionKeys;
import com.bifos.assistant.crypto.infra.ServerSecretRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionTemplate;

/** 서버 비밀값을 KEK 로 감싸 쓰고 읽고 비우는지, 캐시가 옛 값을 남기지 않는지 확인한다. 검사용 KEK 파일을 쓴다. */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class DatabaseServerSecretsTest {

    private static final String VALUE = "plain-marker-7781-server-secret-value";

    @Autowired
    DatabaseServerSecrets secrets;

    @Autowired
    ServerSecretRepository rows;

    @Autowired
    DataKeyRewrapper rewrapper;

    @Autowired
    DataEncryptionProperties properties;

    @Autowired
    TransactionTemplate transactions;


    @Test
    @DisplayName("쓴 값은 줄에 암호문으로만 있고 읽으면 원문이 나오며 로그에는 이름과 사용자만 남는다")
    void writesSealedValueAndReadsPlain(CapturedOutput output) {
        secrets.write("probe_secret", VALUE, Map.of("note", "public"), 7L);

        ServerSecret row = rows.findById("probe_secret").orElseThrow();
        assertThat(row.sealedValue()).startsWith("v1.").doesNotContain(VALUE);
        assertThat(row.kekId()).isEqualTo("test-kek-1");
        assertThat(row.prefix()).isEqualTo("plai");
        assertThat(row.updatedBy()).isEqualTo(7L);
        assertThat(secrets.read("probe_secret")).contains(VALUE);
        assertThat(secrets.find("probe_secret")).get().satisfies(record -> {
            assertThat(record.set()).isTrue();
            assertThat(record.meta()).containsEntry("note", "public");
        });
        assertThat(output.getAll()).contains("server secret written name=probe_secret by=7")
            .doesNotContain(VALUE)
            .doesNotContain("plai");
    }


    @Test
    @DisplayName("암호문을 다른 이름의 줄로 옮기면 풀리지 않는다")
    void sealedValueIsBoundToName() {
        secrets.write("probe_a", VALUE, Map.of(), 1L);
        ServerSecret source = rows.findById("probe_a").orElseThrow();
        secrets.write("probe_b", "other-value-0000000000", Map.of(), 1L);
        ServerSecret target = rows.findById("probe_b").orElseThrow();
        target.rewrap(source.kekId(), source.sealedValue());
        rows.save(target);
        secrets.invalidateAll();

        assertThat(secrets.read("probe_b")).isEmpty();
    }


    @Test
    @DisplayName("지우면 줄은 남고 값은 비며 같은 이름에 다시 쓸 수 있다")
    void clearKeepsRowAndAllowsRewrite() {
        secrets.write("probe_clear", VALUE, Map.of(), 1L);

        secrets.clear("probe_clear", 2L);

        assertThat(secrets.exists("probe_clear")).isTrue();
        assertThat(secrets.read("probe_clear")).isEmpty();
        ServerSecret row = rows.findById("probe_clear").orElseThrow();
        assertThat(row.sealedValue()).isNull();
        assertThat(row.prefix()).isNull();
        assertThat(row.updatedBy()).isEqualTo(2L);

        secrets.write("probe_clear", "second-value-1111111111", Map.of(), 3L);
        assertThat(secrets.read("probe_clear")).contains("second-value-1111111111");
    }


    @Test
    @DisplayName("되돌린 트랜잭션의 쓰기는 읽기에 남지 않는다")
    void rolledBackWriteIsNotCached() {
        secrets.write("probe_rollback", VALUE, Map.of(), 1L);

        transactions.executeWithoutResult(status -> {
            secrets.write("probe_rollback", "rolled-back-value-2222", Map.of(), 1L);
            status.setRollbackOnly();
        });

        assertThat(secrets.read("probe_rollback")).contains(VALUE);
    }


    @Test
    @DisplayName("쓰기 커밋 전에 옛 줄을 읽은 값은 커밋 뒤 캐시에 남지 않는다")
    void readBeforeCommitDoesNotOverwriteNewValue() {
        secrets.write("probe_race", VALUE, Map.of(), 1L);
        secrets.invalidateAll();

        transactions.executeWithoutResult(status -> {
            secrets.write("probe_race", "new-value-after-commit-33", Map.of(), 1L);
            // 커밋 전에 다른 읽기가 옛 줄을 읽어 캐시를 채우려 한다.
            Thread reader = Thread.ofVirtual().start(() -> secrets.read("probe_race"));
            try {
                reader.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(secrets.read("probe_race")).contains("new-value-after-commit-33");
    }


    @Test
    @DisplayName("옛 KEK 로 감싼 줄은 기동 작업이 활성 KEK 로 다시 감싼다")
    void rewrapsRowsWrappedWithOldKek() {
        FileKeyEncryptionKeys oldKeks = new FileKeyEncryptionKeys(
            new DataEncryptionProperties(properties.kekFile(), "test-kek-0", true, properties.dekCacheTtl(), 10, null, 0));
        secrets.write("probe_rewrap", "placeholder-value-44444", Map.of(), 1L);
        ServerSecret row = rows.findById("probe_rewrap").orElseThrow();
        row.rewrap(
            "test-kek-0",
            oldKeks.wrap(VALUE.getBytes(StandardCharsets.UTF_8), "server_secret:probe_rewrap".getBytes(StandardCharsets.UTF_8)));
        rows.save(row);

        rewrapper.run(null);

        assertThat(rows.findById("probe_rewrap")).get().extracting(ServerSecret::kekId).isEqualTo("test-kek-1");
        assertThat(secrets.read("probe_rewrap")).isEqualTo(Optional.of(VALUE));
    }


    @Test
    @DisplayName("빈 값은 쓰지 않는다")
    void rejectsEmptyValue() {
        assertThatThrownBy(() -> secrets.write("probe_empty", "", Map.of(), 1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(secrets.exists("probe_empty")).isFalse();
    }
}
