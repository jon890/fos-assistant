package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 민감 Memory 본문 하나를 AES-256-GCM 으로 암호화하고 푼다(ADR-055).
 *
 * <p>key 가 없으면 암호화 요청을 거절한다. 평문을 돌려주는 길은 없다.
 * {@code binding} 은 이 암호문이 누구의 것인가를 나타내는 글이고, 이 클래스는 그 뜻을 모른다.
 */
@Slf4j
@Component
public class MemoryContentCipher {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String activeKeyId;
    private final Map<String, SecretKey> keys = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public MemoryContentCipher(MemoryEncryptionProperties properties) {
        this.activeKeyId = properties.activeKeyId();
        properties.parsedKeys().forEach((id, bytes) -> keys.put(id, new SecretKeySpec(bytes, "AES")));
    }

    public boolean enabled() {
        return !activeKeyId.isEmpty();
    }

    public StoredContent seal(String plain, String binding) {
        if (!enabled()) {
            throw new ApiException(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE, "Memory encryption key is not configured");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String content = VERSION + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
            return new StoredContent(content, activeKeyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Memory content encryption failed", e);
        }
    }

    public String open(String stored, String keyId, String binding) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new ApiException(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE, "Memory encryption key is not available");
        }
        String[] pieces = stored.split("\\.", -1);
        if (pieces.length != 3 || !VERSION.equals(pieces[0])) {
            throw new IllegalStateException("Memory content is not in the sealed format");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(pieces[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(decoder.decode(pieces[2])), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            log.error("Memory 본문의 태그가 맞지 않는다 keyId={}", keyId);
            throw new IllegalStateException("Memory content authentication failed");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("Memory 본문을 풀지 못했다 keyId={}", keyId);
            throw new IllegalStateException("Memory content decryption failed");
        }
    }
}
