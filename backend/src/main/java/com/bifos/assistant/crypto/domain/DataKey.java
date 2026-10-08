package com.bifos.assistant.crypto.domain;

import javax.crypto.SecretKey;

/**
 * 푼 데이터 key 하나와 그 주인이다. 메모리에만 둔다.
 *
 * <p>record 로 두지 않는다. 자동으로 생기는 {@code toString} 과 {@code equals} 가 key 를 다루게 하지 않는다.
 */
public final class DataKey {

    private final Long id;
    private final Long userId;
    private final SecretKey secret;

    public DataKey(Long id, Long userId, SecretKey secret) {
        this.id = id;
        this.userId = userId;
        this.secret = secret;
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public SecretKey secret() {
        return secret;
    }

    @Override
    public String toString() {
        return "DataKey[id=" + id + ", userId=" + userId + "]";
    }
}
