package com.bifos.assistant.crypto.domain;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * AES-256-GCM 한 번의 암호화와 복호화다. 모양은 ADR-055 의 {@code v1.<IV>.<암호문과 태그>} 를 그대로 쓴다.
 *
 * <p>IV 는 부를 때마다 12바이트를 새로 뽑는다. 인증 태그는 128비트다. 실패는 {@link IllegalStateException} 하나로 낸다.
 * 예외 메시지에 본문과 key 를 넣지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AesGcm {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String seal(SecretKey key, byte[] plain, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plain);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed");
        }
    }

    public static byte[] open(SecretKey key, String sealed, byte[] aad) {
        String[] pieces = sealed.split("\\.", -1);
        if (pieces.length != 3 || !VERSION.equals(pieces[0])) {
            throw new IllegalStateException("not in the sealed format");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(pieces[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(decoder.decode(pieces[2]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("decryption failed");
        }
    }
}
