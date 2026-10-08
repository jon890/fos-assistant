package com.bifos.assistant.crypto.application;

import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.domain.UserDataKey;
import com.bifos.assistant.crypto.infra.UserDataKeyRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데이터 key 줄을 부르는 쪽과 다른 트랜잭션에서 읽고 쓴다.
 *
 * <p>메시지 저장은 대화 줄을 잠근 트랜잭션 안에서 데이터 key 를 찾는다. 같은 사용자의 첫 메시지 둘이 나란히 key 를 만들면 한쪽이
 * 유일 제약에 걸리는데, 그 실패가 메시지 저장까지 되돌리지 않게 새 트랜잭션에서 만든다. 다시 읽을 때도 새 트랜잭션이어야 다른 쪽이
 * 커밋한 줄이 보인다.
 */
@Component
@RequiredArgsConstructor
public class DataKeyWriter {

    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDataKeyRepository keys;
    private final KeyEncryptionKeys keks;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<UserDataKey> find(Long userId) {
        return keys.findByUserId(userId);
    }

    /** 새 데이터 key 를 뽑아 활성 KEK 로 감싸 저장한다. 이미 있으면 유일 제약 위반이 난다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserDataKey create(Long userId) {
        byte[] dataKey = new byte[KEY_BYTES];
        RANDOM.nextBytes(dataKey);
        String wrapped = keks.wrap(dataKey, UserDataKey.wrapBinding(userId));
        return keys.saveAndFlush(UserDataKey.wrapped(userId, keks.activeKeyId(), wrapped, clock.instant()));
    }

    /**
     * 활성 KEK 가 아닌 KEK 로 감싼 줄 하나를 활성 KEK 로 다시 감싼다. 데이터 key 원문은 그대로라 본문은 다시 쓰지 않는다.
     *
     * @return 다시 감쌌으면 참. 그 줄의 옛 KEK 를 갖고 있지 않으면 거짓
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rewrap(Long id) {
        UserDataKey row = keys.findById(id).orElse(null);
        if (row == null || row.kekId().equals(keks.activeKeyId())) {
            return false;
        }
        if (!keks.has(row.kekId())) {
            return false;
        }
        byte[] binding = UserDataKey.wrapBinding(row.userId());
        byte[] dataKey = keks.unwrap(row.kekId(), row.wrappedKey(), binding);
        row.rewrap(keks.activeKeyId(), keks.wrap(dataKey, binding), clock.instant());
        return true;
    }
}
