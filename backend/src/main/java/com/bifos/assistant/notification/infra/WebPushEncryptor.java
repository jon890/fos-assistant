package com.bifos.assistant.notification.infra;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 웹 푸시 글을 RFC 8291 의 {@code aes128gcm} 로 기기마다 암호화한다(ADR-20261120 / web-push).
 *
 * <p>보낼 때마다 서버 쪽 임시 P-256 키 쌍과 salt 를 새로 만든다. 레코드 하나(rs 4096)에 글 전체를 담고 끝에 구분 바이트 {@code 0x02}
 * 를 붙인다. 외부 라이브러리 없이 JDK 의 ECDH, HMAC-SHA256(HKDF), AES-GCM 으로 쓴다.
 */
@Component
public class WebPushEncryptor {

    /** 글의 상한이다. 푸시 서비스가 받는 본문 4096바이트에서 머리 86, GCM 태그 16, 구분 바이트 1 을 뺀 값이다. */
    public static final int MAX_PLAINTEXT = 4096 - 16 - 1 - 86;

    private static final int RECORD_SIZE = 4096;
    private static final int SALT_BYTES = 16;
    private static final int AUTH_BYTES = 16;
    private static final int TAG_BITS = 128;
    private static final byte[] KEY_INFO = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private final SecureRandom random = new SecureRandom();


    /**
     * @param plaintext 보낼 글
     * @param uaPublic 브라우저의 P-256 공개 점 65바이트({@code p256dh})
     * @param authSecret 브라우저의 인증 값 16바이트({@code auth})
     * @return 머리(salt, rs, 서버 공개 키)와 암호문을 이은 요청 본문
     */
    public byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, newServerKeys(), salt);
    }


    /** 서버 키 쌍과 salt 를 받는 형태다. RFC 8291 의 예시 값으로 검사할 때 쓴다. */
    byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair serverKeys, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("web push payload is too large: " + plaintext.length + " bytes");
        }
        if (authSecret.length != AUTH_BYTES || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("web push auth secret and salt must be 16 bytes");
        }
        ECPublicKey uaKey = P256.publicKey(uaPublic);
        byte[] asPublic = P256.encode((ECPublicKey)serverKeys.getPublic());
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(serverKeys.getPrivate());
            agreement.doPhase(uaKey, true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] ikm = hmac(prkKey, concat(KEY_INFO, uaPublic, asPublic, new byte[] {1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[] {1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {2}));

            ByteBuffer header = ByteBuffer.allocate(SALT_BYTES + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte)asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("web push encryption failed", ex);
        }
    }


    private static KeyPair newServerKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256.params());
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("P-256 key pair cannot be generated", ex);
        }
    }


    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }


    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
