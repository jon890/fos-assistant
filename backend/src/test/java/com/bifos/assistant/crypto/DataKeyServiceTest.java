package com.bifos.assistant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.crypto.application.DataKeyRewrapper;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.domain.AesGcm;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.UserDataKey;
import com.bifos.assistant.crypto.infra.DataEncryptionProperties;
import com.bifos.assistant.crypto.infra.FileKeyEncryptionKeys;
import com.bifos.assistant.crypto.infra.UserDataKeyRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 사용자별 데이터 key 를 만들고 풀고 KEK 를 바꿀 때 다시 감싸는지 확인한다. 검사용 KEK 파일을 쓴다. */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class DataKeyServiceTest {

    private static final String PLAIN = "평문-표식-5521 혈압약 복용 기록";

    @Autowired
    DataKeyService cipher;

    @Autowired
    DataKeyRewrapper rewrapper;

    @Autowired
    UserDataKeyRepository keys;

    @Autowired
    DataEncryptionProperties properties;

    @AfterEach
    void tearDown() {
        cipher.forgetCachedKeys();
    }

    @Test
    @DisplayName("같은 사용자의 본문은 데이터 key 하나로 암호화되고 같은 AAD 로만 풀린다")
    void sealsWithOneKeyPerUserAndOpensOnlyWithSameAad() {
        Long userId = anyUserId();

        SealedText first = cipher.seal(userId, "row:1", PLAIN).orElseThrow();
        SealedText second = cipher.seal(userId, "row:2", PLAIN).orElseThrow();

        assertThat(first.content()).startsWith("v1.").doesNotContain(PLAIN);
        assertThat(first.content()).isNotEqualTo(second.content());
        assertThat(first.keyId()).isEqualTo(second.keyId());
        assertThat(keys.findByUserId(userId)).get().satisfies(row -> {
            assertThat(row.kekId()).isEqualTo("test-kek-1");
            assertThat(row.wrappedKey()).startsWith("v1.");
        });
        assertThat(cipher.open(first.keyId(), userId, "row:1", first.content())).contains(PLAIN);
        assertThat(cipher.open(first.keyId(), userId, "row:2", first.content())).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 데이터 key 로 쓴 본문은 주인을 바꿔 불러도 풀리지 않는다")
    void refusesToOpenWithAnotherOwner() {
        Long owner = anyUserId();
        Long stranger = anyUserId();
        SealedText sealed = cipher.seal(owner, "row:9", PLAIN).orElseThrow();
        cipher.seal(stranger, "row:9", "다른 사람 글").orElseThrow();

        assertThat(cipher.open(sealed.keyId(), stranger, "row:9", sealed.content()))
                .isEmpty();
    }

    @Test
    @DisplayName("데이터 key 줄을 지우면 메모리에서 비운 뒤로 그 본문을 풀지 못한다")
    void cannotOpenAfterDataKeyIsDestroyed() {
        Long userId = anyUserId();
        SealedText sealed = cipher.seal(userId, "row:3", PLAIN).orElseThrow();

        keys.deleteById(sealed.keyId());
        cipher.forgetCachedKeys();

        assertThat(cipher.open(sealed.keyId(), userId, "row:3", sealed.content()))
                .isEmpty();
    }

    @Test
    @DisplayName("풀지 못한 데이터 key 의 경고는 1분 안에 한 번만 남긴다")
    void warnsOnceForUnavailableDataKey(CapturedOutput output) {
        Long userId = anyUserId();
        SealedText sealed = cipher.seal(userId, "row:5", PLAIN).orElseThrow();
        keys.deleteById(sealed.keyId());
        cipher.forgetCachedKeys();

        for (int i = 0; i < 5; i++) {
            assertThat(cipher.open(sealed.keyId(), userId, "row:5", sealed.content()))
                    .isEmpty();
        }

        String marker = "데이터 key 를 풀지 못했다. 1분 동안 이 key 의 본문은 읽을 수 없는 것으로 본다 keyId=" + sealed.keyId() + " ";
        assertThat(output.getAll().split(Pattern.quote(marker), -1)).hasSize(2);
    }

    @Test
    @DisplayName("옛 KEK 로 감싼 데이터 key 는 기동 작업이 활성 KEK 로 다시 감싸고 본문은 그대로 풀린다")
    void rewrapsKeysWrappedWithOldKek() {
        Long userId = anyUserId();
        FileKeyEncryptionKeys oldKeks = new FileKeyEncryptionKeys(
                new DataEncryptionProperties(properties.kekFile(), "test-kek-0", true, properties.dekCacheTtl(), 10));
        byte[] dataKey = new byte[32];
        Arrays.fill(dataKey, (byte) 3);
        UserDataKey row = keys.saveAndFlush(UserDataKey.wrapped(
                userId, "test-kek-0", oldKeks.wrap(dataKey, UserDataKey.wrapBinding(userId)), Instant.now()));
        String sealed = AesGcm.seal(
                new SecretKeySpec(dataKey, "AES"),
                PLAIN.getBytes(StandardCharsets.UTF_8),
                "row:4".getBytes(StandardCharsets.UTF_8));

        int rewrapped = rewrapper.rewrapAll();

        assertThat(rewrapped).isGreaterThanOrEqualTo(1);
        UserDataKey after = keys.findById(row.id()).orElseThrow();
        assertThat(after.kekId()).isEqualTo("test-kek-1");
        assertThat(after.rewrappedAt()).isNotNull();
        assertThat(after.wrappedKey()).isNotEqualTo(row.wrappedKey());
        assertThat(cipher.open(row.id(), userId, "row:4", sealed)).contains(PLAIN);
    }

    private static Long anyUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, 9_000_000L);
    }
}
