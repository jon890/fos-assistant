package com.bifos.assistant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.crypto.domain.UserDataKey;
import com.bifos.assistant.crypto.infra.DataEncryptionProperties;
import com.bifos.assistant.crypto.infra.FileKeyEncryptionKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileKeyEncryptionKeysTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("파일의 KEK 로 감싼 데이터 key 는 같은 주인의 AAD 로만 풀린다")
    void wrapsAndUnwrapsOnlyForSameOwner() throws IOException {
        FileKeyEncryptionKeys keks = keks(file("# 주석\n\nkek-a:" + keyOf(1) + "\nkek-b:" + keyOf(2) + "\n"), "kek-b");
        byte[] dataKey = new byte[32];
        Arrays.fill(dataKey, (byte) 7);

        String wrapped = keks.wrap(dataKey, UserDataKey.wrapBinding(10L));

        assertThat(keks.available()).isTrue();
        assertThat(keks.activeKeyId()).isEqualTo("kek-b");
        assertThat(keks.has("kek-a")).isTrue();
        assertThat(keks.unwrap("kek-b", wrapped, UserDataKey.wrapBinding(10L))).isEqualTo(dataKey);
        assertThatThrownBy(() -> keks.unwrap("kek-b", wrapped, UserDataKey.wrapBinding(11L)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> keks.unwrap("kek-a", wrapped, UserDataKey.wrapBinding(10L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("설정이 비어 있으면 꺼진 채로 뜨고 감싸기를 거절한다")
    void startsDisabledWithoutSettings() {
        FileKeyEncryptionKeys keks = new FileKeyEncryptionKeys(properties("", "", false));

        assertThat(keks.available()).isFalse();
        assertThat(keks.activeKeyId()).isEmpty();
        assertThatThrownBy(() -> keks.wrap(new byte[32], UserDataKey.wrapBinding(1L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("required 인데 KEK 설정이 비어 있으면 기동하지 않는다")
    void refusesToStartWhenRequiredWithoutKek() {
        assertThatThrownBy(() -> properties("", "", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("경로와 활성 id 가운데 하나만 있으면 기동하지 않는다")
    void refusesHalfSettings() {
        assertThatThrownBy(() -> properties("/somewhere", "", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("", "kek-a", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("모양이 틀린 KEK 파일은 기동을 멈추고 메시지에 key 값을 담지 않는다")
    void rejectsMalformedFilesWithoutLeakingValues() throws IOException {
        String notBase64 = "kek-a:%%%%secret-looking%%%%";
        String shortKey = "kek-a:" + Base64.getEncoder().encodeToString(new byte[16]);
        String duplicated = "kek-a:" + keyOf(1) + "\nkek-a:" + keyOf(2);
        String badId = "KEK_A:" + keyOf(1);

        for (String content : new String[] {notBase64, shortKey, duplicated, badId}) {
            Path file = file(content);
            assertThatThrownBy(() -> keks(file, "kek-a"))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(error -> assertThat(error.getMessage())
                            .doesNotContain("secret-looking")
                            .doesNotContain(keyOf(1))
                            .doesNotContain(file.toString()));
        }
    }

    @Test
    @DisplayName("활성 id 가 파일에 없으면 기동을 멈춘다")
    void rejectsActiveIdMissingFromFile() throws IOException {
        Path file = file("kek-a:" + keyOf(1));

        assertThatThrownBy(() -> keks(file, "kek-z")).isInstanceOf(IllegalStateException.class);
    }

    private FileKeyEncryptionKeys keks(Path file, String activeId) {
        return new FileKeyEncryptionKeys(properties(file.toString(), activeId, true));
    }

    private static DataEncryptionProperties properties(String file, String activeId, boolean required) {
        return new DataEncryptionProperties(file, activeId, required, Duration.ofMinutes(10), 1000);
    }

    private Path file(String content) throws IOException {
        Path file = Files.createTempFile(dir, "kek", ".keys");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String keyOf(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
