package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HermesProfileKeyStoreTest {

    private HermesProfileKeyStore storeAt(Path dir) {
        return new HermesProfileKeyStore(
                new HermesProperties(
                        dir.toString(),
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
}
