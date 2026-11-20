package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.notification.config.PushProperties;
import com.bifos.assistant.notification.infra.P256;
import com.bifos.assistant.notification.infra.VapidKeys;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** VAPID 키를 읽고 한 쌍인지 기동 때 확인하는지 본다(ADR-20261120 / web-push). */
class VapidKeysTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @TempDir
    Path dir;


    @Test
    @DisplayName("맞는 키 쌍이면 켜지고 같은 공개 키를 준다")
    void loadsMatchingPair() throws Exception {
        KeyPair pair = newPair();
        String publicKey = B64.encodeToString(P256.encode((ECPublicKey)pair.getPublic()));

        VapidKeys keys = new VapidKeys(properties(publicKey, writePrivate(pair), "mailto:a@example.invalid"));

        assertThat(keys.enabled()).isTrue();
        assertThat(keys.publicKeyBase64Url()).isEqualTo(publicKey);
        assertThat(keys.subject()).isEqualTo("mailto:a@example.invalid");
    }


    @Test
    @DisplayName("공개 키와 비밀 키가 한 쌍이 아니면 기동하지 않는다")
    void rejectsMismatchedPair() throws Exception {
        String otherPublic = B64.encodeToString(P256.encode((ECPublicKey)newPair().getPublic()));
        PushProperties properties = properties(otherPublic, writePrivate(newPair()), "mailto:a@example.invalid");

        assertThatThrownBy(() -> new VapidKeys(properties)).isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("비밀 키 파일이 깨졌으면 기동하지 않고 메시지에 파일 내용을 넣지 않는다")
    void rejectsBrokenFile() throws Exception {
        Path file = dir.resolve("broken.key");
        Files.writeString(file, "not-a-key!!");
        String publicKey = B64.encodeToString(P256.encode((ECPublicKey)newPair().getPublic()));
        PushProperties properties = properties(publicKey, file.toString(), "mailto:a@example.invalid");

        assertThatThrownBy(() -> new VapidKeys(properties))
            .isInstanceOf(RuntimeException.class)
            .hasMessageNotContaining("not-a-key");
    }


    @Test
    @DisplayName("셋 다 비면 꺼진 채 만들어지고, 하나만 채우면 설정이 거절된다")
    void offWhenEmpty() {
        VapidKeys keys = new VapidKeys(properties("", "", ""));

        assertThat(keys.enabled()).isFalse();
        assertThat(keys.publicKeyBase64Url()).isNull();
        assertThatThrownBy(() -> properties("abc", "", "")).isInstanceOf(IllegalArgumentException.class);
    }


    private String writePrivate(KeyPair pair) throws Exception {
        BigInteger scalar = ((ECPrivateKey)pair.getPrivate()).getS();
        byte[] raw = scalar.toByteArray();
        byte[] fixed = new byte[32];
        int from = Math.max(0, raw.length - 32);
        System.arraycopy(raw, from, fixed, 32 - (raw.length - from), raw.length - from);
        Path file = dir.resolve("vapid-" + System.nanoTime() + ".key");
        Files.writeString(file, B64.encodeToString(fixed) + "\n");
        return file.toString();
    }


    private static KeyPair newPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }


    private static PushProperties properties(String publicKey, String file, String subject) {
        return new PushProperties(publicKey, file, subject, List.of("fcm.googleapis.com"),
            Duration.ofSeconds(5), Duration.ofSeconds(10), List.of(), Duration.ofSeconds(30), 10);
    }
}
