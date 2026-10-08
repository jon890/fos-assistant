package com.bifos.assistant.crypto.application;

import com.bifos.assistant.crypto.domain.AesGcm;
import com.bifos.assistant.crypto.domain.DataKey;
import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.crypto.domain.UserDataKey;
import com.bifos.assistant.crypto.infra.DataEncryptionProperties;
import com.bifos.assistant.crypto.infra.UserDataKeyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 사용자별 데이터 key 로 본문을 암호화하고 푼다(ADR-20261008 / data-encryption).
 *
 * <p>데이터 key 는 사용자마다 하나이고 KEK 로 감싸 {@code user_data_key} 에 둔다. 푼 key 는 정한 수명과 개수 안에서 메모리에
 * 둔다. 그래서 같은 사용자의 본문을 여러 개 풀 때 KEK 풀기는 처음 한 번만 돈다.
 *
 * <p>풀지 못한 까닭은 데이터 key 번호와 사용자 번호만 경고 로그에 남긴다. 본문과 key 와 AAD 는 남기지 않는다.
 */
@Component
@Slf4j
public class DataKeyService implements TextCipher {

    private static final Duration UNAVAILABLE_TTL = Duration.ofMinutes(1);

    private final KeyEncryptionKeys keks;
    private final DataKeyWriter writer;
    private final UserDataKeyRepository keys;
    private final KeyCache<Long, DataKey> byId;
    private final KeyCache<Long, DataKey> byUser;

    /**
     * 풀지 못한 데이터 key 번호다. 1분 동안 다시 읽지 않고 경고도 다시 남기지 않는다. KEK 를 뺐거나 데이터 key 줄을 지웠을 때
     * 그 사용자의 메시지마다 조회와 경고가 되풀이되지 않게 한다.
     */
    private final KeyCache<Long, Boolean> unavailable;

    public DataKeyService(
            KeyEncryptionKeys keks,
            DataKeyWriter writer,
            UserDataKeyRepository keys,
            DataEncryptionProperties properties,
            Clock clock) {
        this.keks = keks;
        this.writer = writer;
        this.keys = keys;
        this.byId = new KeyCache<>(properties.dekCacheTtl(), properties.dekCacheMaxEntries(), clock);
        this.byUser = new KeyCache<>(properties.dekCacheTtl(), properties.dekCacheMaxEntries(), clock);
        this.unavailable = new KeyCache<>(UNAVAILABLE_TTL, properties.dekCacheMaxEntries(), clock);
    }

    @Override
    public boolean enabled() {
        return keks.available();
    }

    @Override
    public Optional<SealedText> seal(Long ownerUserId, String aad, String plain) {
        if (!enabled()) {
            return Optional.empty();
        }
        DataKey key = forWriting(ownerUserId);
        String content = AesGcm.seal(key.secret(), plain.getBytes(StandardCharsets.UTF_8), bytes(aad));
        return Optional.of(new SealedText(content, key.id()));
    }

    @Override
    public Optional<String> open(Long keyId, Long ownerUserId, String aad, String sealed) {
        if (unavailable.get(keyId).isPresent()) {
            return Optional.empty();
        }
        DataKey key;
        try {
            key = forReading(keyId);
        } catch (IllegalStateException e) {
            unavailable.put(keyId, Boolean.TRUE);
            log.warn("데이터 key 를 풀지 못했다. 1분 동안 이 key 의 본문은 읽을 수 없는 것으로 본다 keyId={} reason={}", keyId, e.getMessage());
            return Optional.empty();
        }
        try {
            if (!key.userId().equals(ownerUserId)) {
                log.warn("데이터 key 의 주인이 본문의 주인과 다르다 keyId={} ownerUserId={}", keyId, ownerUserId);
                return Optional.empty();
            }
            return Optional.of(new String(AesGcm.open(key.secret(), sealed, bytes(aad)), StandardCharsets.UTF_8));
        } catch (IllegalStateException e) {
            log.warn("본문을 풀지 못했다 keyId={} ownerUserId={} reason={}", keyId, ownerUserId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 메모리에 둔 데이터 key 를 모두 버린다. 검사가 key 를 바꿀 때 쓴다 */
    public void forgetCachedKeys() {
        byId.clear();
        byUser.clear();
        unavailable.clear();
    }

    private DataKey forWriting(Long userId) {
        Optional<DataKey> cached = byUser.get(userId);
        if (cached.isPresent()) {
            return cached.get();
        }
        UserDataKey row = writer.find(userId).orElseGet(() -> create(userId));
        return remember(row);
    }

    private UserDataKey create(Long userId) {
        try {
            return writer.create(userId);
        } catch (DataIntegrityViolationException e) {
            // 같은 사용자의 다른 저장이 먼저 만들었다. 새 트랜잭션에서 다시 읽으면 그 줄이 보인다.
            return writer.find(userId).orElseThrow(() -> e);
        }
    }

    private DataKey forReading(Long keyId) {
        Optional<DataKey> cached = byId.get(keyId);
        if (cached.isPresent()) {
            return cached.get();
        }
        UserDataKey row = keys.findById(keyId).orElseThrow(() -> new IllegalStateException("data key does not exist"));
        return remember(row);
    }

    private DataKey remember(UserDataKey row) {
        byte[] secret = keks.unwrap(row.kekId(), row.wrappedKey(), UserDataKey.wrapBinding(row.userId()));
        DataKey key = new DataKey(row.id(), row.userId(), new SecretKeySpec(secret, "AES"));
        byId.put(row.id(), key);
        byUser.put(row.userId(), key);
        return key;
    }

    private static byte[] bytes(String aad) {
        return aad.getBytes(StandardCharsets.UTF_8);
    }
}
