package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HermesProfileKeyStoreTest {

    private HermesProfileKeyStore storeAt(Path dir) {
        return new HermesProfileKeyStore(
                new HermesProperties(
                        dir.toString(),
                        "https://hermes-dashboard.example.com",
                        "test-dashboard-token",
                        "https://hermes-listener.example.com",
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)));
    }

    @Test
    void reads_the_key_that_belongs_to_the_requested_profile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("dad"), "dad-key\n");
        Files.writeString(dir.resolve("mom"), "mom-key\n");

        assertThat(storeAt(dir).resolve("dad")).isEqualTo("dad-key");
        assertThat(storeAt(dir).resolve("mom")).isEqualTo("mom-key");
    }

    @Test
    void refuses_a_profile_without_a_key_instead_of_borrowing_another_one(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("dad"), "dad-key");

        assertThatThrownBy(() -> storeAt(dir).resolve("kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_PROFILE_KEY_MISSING);
    }

    @Test
    void refuses_an_empty_key_file(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("dad"), "   \n");

        assertThatThrownBy(() -> storeAt(dir).resolve("dad")).isInstanceOf(ApiException.class);
    }

    @Test
    void refuses_a_profile_name_that_could_escape_the_key_directory(@TempDir Path dir) {
        assertThatThrownBy(() -> storeAt(dir).resolve("../../etc/passwd"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_PROFILE_KEY_MISSING);
    }

    @Test
    void reads_back_the_key_it_wrote(@TempDir Path dir) {
        storeAt(dir).write("kid", "kid-key");

        assertThat(storeAt(dir).resolve("kid")).isEqualTo("kid-key");
    }

    /** 이 컨테이너 안의 다른 프로세스가 남의 credential 을 읽지 못하게 주인만 읽고 쓴다. */
    @Test
    void writes_a_key_file_that_only_its_owner_can_read(@TempDir Path dir) throws IOException {
        storeAt(dir).write("kid", "kid-key");

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dir.resolve("kid"));
        assertThat(permissions)
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    /** 덮으면 그 profile 로 돌던 대화가 다음 요청부터 401 을 받는데 그 원인이 드러나지 않는다. */
    @Test
    void refuses_to_overwrite_a_key_that_is_already_there(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("kid"), "already-there");

        assertThatThrownBy(() -> storeAt(dir).write("kid", "kid-key"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_PROVISION_FAILED);
        assertThat(Files.readString(dir.resolve("kid"))).isEqualTo("already-there");
    }

    @Test
    void refuses_to_write_under_a_profile_name_that_could_escape_the_key_directory(@TempDir Path dir) {
        assertThatThrownBy(() -> storeAt(dir).write("../escaped", "kid-key"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(dir.toFile().list()).isEmpty();
        assertThat(dir.resolveSibling("escaped")).doesNotExist();
    }

    @Test
    void removes_the_key_it_wrote_and_passes_over_a_name_it_never_wrote(@TempDir Path dir) {
        HermesProfileKeyStore store = storeAt(dir);
        store.write("kid", "kid-key");

        store.delete("kid");
        store.delete("kid");

        assertThat(dir.resolve("kid")).doesNotExist();
    }
}
