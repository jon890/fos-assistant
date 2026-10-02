package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryContentCipher;
import com.bifos.assistant.memory.application.MemoryEncryptionProperties;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemoryContentCipherTest {

    private static final String KEY_VALUE = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String TEST_KEYS = "test-1:" + KEY_VALUE;
    private static final String PLAIN = "평문-표식-7391";

    private static MemoryContentCipher cipher() {
        return new MemoryContentCipher(new MemoryEncryptionProperties("test-1", TEST_KEYS));
    }

    private static String keyOf(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    @DisplayName("암호화한 글은 같은 binding 으로 풀리고 저장된 글은 평문을 담지 않는다")
    void sealAndOpen() {
        MemoryContentCipher cipher = cipher();

        StoredContent sealed = cipher.seal(PLAIN, "USER:7");

        assertThat(sealed.content()).startsWith("v1.").doesNotContain(PLAIN);
        assertThat(sealed.keyId()).isEqualTo("test-1");
        assertThat(cipher.open(sealed.content(), sealed.keyId(), "USER:7")).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("같은 글을 두 번 암호화하면 암호문이 다르다")
    void ivDiffers() {
        MemoryContentCipher cipher = cipher();

        assertThat(cipher.seal(PLAIN, "USER:7").content())
                .isNotEqualTo(cipher.seal(PLAIN, "USER:7").content());
    }

    @Test
    @DisplayName("binding 이 다르면 풀지 못한다")
    void bindingMismatch() {
        MemoryContentCipher cipher = cipher();
        StoredContent sealed = cipher.seal(PLAIN, "USER:7");

        assertThatThrownBy(() -> cipher.open(sealed.content(), sealed.keyId(), "USER:8"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("암호문이 바뀌면 풀지 못한다")
    void tampered() {
        MemoryContentCipher cipher = cipher();
        StoredContent sealed = cipher.seal(PLAIN, "USER:7");
        String content = sealed.content();
        // 마지막 글자는 base64 의 남는 비트라 바꿔도 같은 바이트로 풀릴 수 있다. 암호문 중간 글자를 바꾼다
        int index = content.length() - 8;
        char target = content.charAt(index);
        String changed = content.substring(0, index) + (target == 'A' ? 'B' : 'A') + content.substring(index + 1);

        assertThatThrownBy(() -> cipher.open(changed, sealed.keyId(), "USER:7"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("목록에 없는 key id 로 풀면 MEMORY_ENCRYPTION_UNAVAILABLE 이다")
    void unknownKeyId() {
        MemoryContentCipher cipher = cipher();
        StoredContent sealed = cipher.seal(PLAIN, "USER:7");

        assertThatThrownBy(() -> cipher.open(sealed.content(), "gone-1", "USER:7"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE));
    }

    @Test
    @DisplayName("key 가 없으면 암호화를 거절하고 평문을 돌려주지 않는다")
    void disabled() {
        MemoryContentCipher cipher = new MemoryContentCipher(new MemoryEncryptionProperties("", ""));

        assertThat(cipher.enabled()).isFalse();
        assertThatThrownBy(() -> cipher.seal(PLAIN, "USER:7"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE));
    }

    @Test
    @DisplayName("옛 key 로 쓴 글을 풀고 새 글은 활성 key 로 쓴다")
    void rotation() {
        MemoryContentCipher oldOnly =
                new MemoryContentCipher(new MemoryEncryptionProperties("old-1", "old-1:" + keyOf(1)));
        MemoryContentCipher rotated = new MemoryContentCipher(
                new MemoryEncryptionProperties("new-1", "old-1:" + keyOf(1) + ",new-1:" + keyOf(2)));
        StoredContent oldSealed = oldOnly.seal(PLAIN, "USER:7");

        assertThat(rotated.open(oldSealed.content(), oldSealed.keyId(), "USER:7"))
                .isEqualTo(PLAIN);
        assertThat(rotated.seal(PLAIN, "USER:7").keyId()).isEqualTo("new-1");
    }

    @Test
    @DisplayName("active-key-id 와 keys 중 한쪽만 비면 설정을 만들지 못한다")
    void halfConfigured() {
        assertThatThrownBy(() -> new MemoryEncryptionProperties("test-1", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryEncryptionProperties("", TEST_KEYS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("active-key-id 가 keys 에 없으면 설정을 만들지 못한다")
    void activeKeyMissing() {
        assertThatThrownBy(() -> new MemoryEncryptionProperties("none", TEST_KEYS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("16바이트 key 는 거절하고 메시지에 key 값을 담지 않는다")
    void shortKey() {
        String shortValue = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new MemoryEncryptionProperties("short-1", "short-1:" + shortValue))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain(shortValue);
    }

    @Test
    @DisplayName("설정의 toString 은 key 값을 담지 않는다")
    void toStringHidesKeys() {
        assertThat(new MemoryEncryptionProperties("test-1", TEST_KEYS).toString())
                .doesNotContain(KEY_VALUE);
    }
}
