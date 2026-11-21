package com.bifos.assistant.notification.infra;

import com.bifos.assistant.notification.domain.VapidKeyPair;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 웹 푸시 요청의 VAPID {@code Authorization} 머리를 만든다(RFC 8292, ADR-20261120 / web-push).
 *
 * <p>JWT 는 ES256 이고 {@code aud} 는 푸시 서비스 주소의 출처, {@code exp} 는 12시간 뒤, {@code sub} 는 설정한 연락처다.
 */
@Component
@RequiredArgsConstructor
public class VapidSigner {

    static final Duration LIFETIME = Duration.ofHours(12);

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HEADER = B64.encodeToString(
        "{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.US_ASCII));

    private final VapidKeys keys;


    /** {@code vapid t=<JWT>, k=<공개 키>} 를 돌려준다. 키가 꺼졌으면 {@link IllegalStateException} 이다. */
    public String authorization(String endpoint, Instant now) {
        VapidKeyPair pair = keys.current().orElseThrow(() -> new IllegalStateException("web push is not configured"));
        URI uri = URI.create(endpoint);
        String audience = (uri.getScheme() + "://" + uri.getHost()).toLowerCase(Locale.ROOT);
        Claims claims = new Claims(audience, now.plus(LIFETIME).getEpochSecond(), pair.subject());
        String unsigned = HEADER + "." + B64.encodeToString(JSON.writeValueAsBytes(claims));
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(pair.privateKey());
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            String token = unsigned + "." + B64.encodeToString(signer.sign());
            return "vapid t=" + token + ", k=" + pair.publicKeyBase64Url();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("VAPID signing failed", ex);
        }
    }


    private record Claims(String aud, long exp, String sub) {
    }
}
