package com.bifos.assistant.notification.infra;

import com.bifos.assistant.notification.config.PushProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 웹 푸시 VAPID 키 쌍을 기동할 때 한 번 읽는다(ADR-20261120 / web-push).
 *
 * <p>설정이 비면 꺼진 채로 둔다. 설정이 있으면 비밀 키 파일을 읽어 P-256 키로 해석하고, 그 비밀 키로 서명한 글을 공개 키가 검증하는지
 * 보아 두 키가 한 쌍인지 확인한다. 하나라도 실패하면 기동을 멈춘다. 예외 메시지와 로그에 키 값과 파일 경로를 넣지 않는다.
 */
@Component
@Slf4j
public class VapidKeys {

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String publicKeyBase64Url;
    private final String subject;


    public VapidKeys(PushProperties properties) {
        if (!properties.configured()) {
            this.publicKey = null;
            this.privateKey = null;
            this.publicKeyBase64Url = null;
            this.subject = null;
            log.warn("웹 푸시 VAPID 키가 설정되지 않아 푸시를 보내지 않는다");
            return;
        }
        this.publicKey = P256.publicKey(decode(properties.vapidPublicKey(), "public key"));
        this.privateKey = P256.privateKey(decode(readFile(properties.vapidPrivateKeyFile()), "private key"));
        requirePair(publicKey, privateKey);
        this.publicKeyBase64Url = ENCODER.encodeToString(P256.encode(publicKey));
        this.subject = properties.subject();
    }


    /** 키가 있어 푸시를 보낼 수 있는가. */
    public boolean enabled() {
        return publicKey != null;
    }


    /** 화면이 구독을 만들 때 쓰는 공개 키다. 꺼졌으면 null 이다. */
    public String publicKeyBase64Url() {
        return publicKeyBase64Url;
    }


    public ECPublicKey publicKey() {
        return publicKey;
    }


    public ECPrivateKey privateKey() {
        return privateKey;
    }


    public String subject() {
        return subject;
    }


    private static String readFile(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.US_ASCII).strip();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("web push: VAPID private key file cannot be read", ex);
        }
    }


    private static byte[] decode(String value, String what) {
        try {
            return DECODER.decode(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("web push: VAPID " + what + " is not base64url", ex);
        }
    }


    private static void requirePair(ECPublicKey publicKey, ECPrivateKey privateKey) {
        byte[] probe = "vapid-key-pair-check".getBytes(StandardCharsets.US_ASCII);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException("web push: VAPID public key does not match the private key");
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("web push: VAPID key pair cannot be checked", ex);
        }
    }
}
