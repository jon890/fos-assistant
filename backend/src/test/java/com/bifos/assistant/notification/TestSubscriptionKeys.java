package com.bifos.assistant.notification;

import com.bifos.assistant.notification.infra.P256;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/** 검사에서 브라우저 구독처럼 쓰는 P-256 공개 키와 16바이트 인증 값이다. 등록 검사를 지나는 모양이다. */
record TestSubscriptionKeys(KeyPair pair, byte[] authSecret) {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();


    static TestSubscriptionKeys create() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256.params());
            byte[] auth = new byte[16];
            new SecureRandom().nextBytes(auth);
            return new TestSubscriptionKeys(generator.generateKeyPair(), auth);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }


    String p256dh() {
        return B64.encodeToString(P256.encode((ECPublicKey)pair.getPublic()));
    }


    String auth() {
        return B64.encodeToString(authSecret);
    }
}
