package com.bifos.assistant.crypto.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 사용자 한 명의 데이터 key(DEK)를 KEK 로 감싼 것이다(ADR-20261008 / data-encryption).
 *
 * <p>사용자마다 한 줄이다. 원문 key 는 저장하지 않는다. KEK 를 바꾸면 이 줄만 새 KEK 로 다시 감싼다. 본문은 다시 쓰지 않는다.
 * 이 줄을 지우면 그 사용자의 암호문은 누구도 풀지 못한다.
 */
@Entity
@Table(name = "user_data_key")
@Accessors(fluent = true)
@Getter
public class UserDataKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "kek_id", nullable = false, length = 32)
    private String kekId;

    /** {@code v1.<IV>.<감싼 key 와 태그>} */
    @Column(name = "wrapped_key", nullable = false, length = 255)
    private String wrappedKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 마지막으로 다른 KEK 로 다시 감싼 시각. 만든 뒤 한 번도 바꾸지 않았으면 비어 있다 */
    @Column(name = "rewrapped_at")
    private Instant rewrappedAt;

    protected UserDataKey() {}

    private UserDataKey(Long userId, String kekId, String wrappedKey, Instant now) {
        this.userId = userId;
        this.kekId = kekId;
        this.wrappedKey = wrappedKey;
        this.createdAt = now;
    }

    public static UserDataKey wrapped(Long userId, String kekId, String wrappedKey, Instant now) {
        return new UserDataKey(userId, kekId, wrappedKey, now);
    }

    /** 감싸기 AAD. 감싼 key 를 다른 사용자의 줄로 옮기면 풀리지 않는다 */
    public static byte[] wrapBinding(Long userId) {
        return ("user_data_key:user:" + userId).getBytes(StandardCharsets.UTF_8);
    }

    public void rewrap(String kekId, String wrappedKey, Instant now) {
        this.kekId = kekId;
        this.wrappedKey = wrappedKey;
        this.rewrappedAt = now;
    }
}
