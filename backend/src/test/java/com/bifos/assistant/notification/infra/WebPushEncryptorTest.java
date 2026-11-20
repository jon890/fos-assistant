package com.bifos.assistant.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 웹 푸시 암호화가 RFC 8291 과 같은 본문을 만드는지 본다(ADR-20261120 / web-push).
 *
 * <p>값은 RFC 8291 의 5절 예시(Appendix A 의 중간값과 같은 입력)를 그대로 옮겼다.
 */
class WebPushEncryptorTest {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String EXPECTED = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private final WebPushEncryptor encryptor = new WebPushEncryptor();


    @Test
    @DisplayName("RFC 8291 의 예시 입력으로 예시와 같은 본문을 만든다")
    void matchesRfcExample() {
        KeyPair server = new KeyPair(P256.publicKey(B64.decode(AS_PUBLIC)), P256.privateKey(B64.decode(AS_PRIVATE)));

        byte[] body = encryptor.encrypt(
            PLAINTEXT.getBytes(StandardCharsets.UTF_8), B64.decode(UA_PUBLIC), B64.decode(AUTH), server, B64.decode(SALT));

        assertThat(body).isEqualTo(B64.decode(EXPECTED));
    }


    @Test
    @DisplayName("상한보다 긴 글은 보내지 않는다")
    void rejectsTooLarge() {
        byte[] tooLarge = new byte[WebPushEncryptor.MAX_PLAINTEXT + 1];

        assertThatThrownBy(() -> encryptor.encrypt(tooLarge, B64.decode(UA_PUBLIC), B64.decode(AUTH)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
