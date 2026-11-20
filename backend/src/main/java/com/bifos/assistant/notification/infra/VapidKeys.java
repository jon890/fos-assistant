package com.bifos.assistant.notification.infra;

import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.notification.domain.VapidKeyPair;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 웹 푸시 VAPID 키 쌍을 서버 비밀값 {@code vapid} 에서 읽는다(ADR-20261120 / web-push, server-secret).
 *
 * <p>부를 때마다 저장소의 지금 값을 본다. 관리자가 화면에서 새로 만들거나 지우면 재기동 없이 다음 사용부터 바뀐다. 값이 없거나 풀 수
 * 없거나 한 쌍이 아니면 꺼진 것으로 본다. 로그에는 예외 종류만 남기고 키 값은 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VapidKeys {

    /** 서버 비밀값의 이름이다. 비밀 키와 공개 키, 연락처를 JSON 한 글로 감싸 둔다. */
    public static final String SECRET_NAME = "vapid";

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ServerSecrets secrets;

    /** 마지막으로 푼 값과 그 해석이다. 같은 글이면 다시 해석하지 않는다. */
    private volatile Parsed last;


    /** 지금 쓸 키 쌍이다. 꺼졌으면 비어 있다. 서명과 공개 키가 어긋나지 않게 한 번 읽은 값을 이어 쓴다. */
    public Optional<VapidKeyPair> current() {
        Optional<String> value = secrets.read(SECRET_NAME);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Parsed cached = last;
        if (cached != null && cached.value().equals(value.get())) {
            return cached.pair();
        }
        Optional<VapidKeyPair> pair = parse(value.get());
        last = new Parsed(value.get(), pair);
        return pair;
    }


    /** 키가 있어 푸시를 보낼 수 있는가. */
    public boolean enabled() {
        return current().isPresent();
    }


    /** 화면이 구독을 만들 때 쓰는 공개 키다. 꺼졌으면 null 이다. */
    public String publicKeyBase64Url() {
        return current().map(VapidKeyPair::publicKeyBase64Url).orElse(null);
    }


    /** 새 키 쌍을 만들어 저장할 글로 감싼다. 비밀 키와 공개 키, 연락처가 들어 있다. */
    public static GeneratedVapidKeys generate(String subject) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            ECPublicKey publicKey = (ECPublicKey)pair.getPublic();
            ECPrivateKey privateKey = (ECPrivateKey)pair.getPrivate();
            byte[] scalar = new byte[32];
            P256.copyFixed(privateKey.getS(), scalar, 0);
            String publicBase64 = ENCODER.encodeToString(P256.encode(publicKey));
            String sealed = JSON.writeValueAsString(new Stored(ENCODER.encodeToString(scalar), publicBase64, subject));
            return new GeneratedVapidKeys(sealed, publicBase64);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("VAPID key pair cannot be generated", ex);
        }
    }


    private Optional<VapidKeyPair> parse(String value) {
        try {
            Stored stored = JSON.readValue(value, Stored.class);
            ECPublicKey publicKey = P256.publicKey(DECODER.decode(stored.publicKey()));
            ECPrivateKey privateKey = P256.privateKey(DECODER.decode(stored.privateKey()));
            requirePair(publicKey, privateKey);
            return Optional.of(new VapidKeyPair(
                publicKey, privateKey, ENCODER.encodeToString(P256.encode(publicKey)), stored.subject()));
        } catch (RuntimeException | GeneralSecurityException ex) {
            // 원인 예외의 메시지에 값이 들어갈 수 있어 붙이지 않고 종류만 적는다.
            log.warn("웹 푸시 VAPID 키를 해석하지 못해 푸시를 끈다 exception={}", ex.getClass().getName());
            return Optional.empty();
        }
    }


    private static void requirePair(ECPublicKey publicKey, ECPrivateKey privateKey) throws GeneralSecurityException {
        byte[] probe = "vapid-key-pair-check".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(probe);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(probe);
        if (!verifier.verify(signature)) {
            throw new GeneralSecurityException("VAPID public key does not match the private key");
        }
    }


    private record Parsed(String value, Optional<VapidKeyPair> pair) {
    }

    private record Stored(String privateKey, String publicKey, String subject) {

        @Override
        public String toString() {
            return "Stored[]";
        }
    }
}
