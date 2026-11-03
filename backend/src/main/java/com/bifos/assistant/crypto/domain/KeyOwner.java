package com.bifos.assistant.crypto.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 데이터 key 한 줄의 주인이다. 사용자 한 명이거나 그룹 하나다(ADR-20261103 / memory-encryption).
 *
 * @param group 참이면 그룹, 거짓이면 사용자다
 * @param id 사용자 번호나 그룹 번호
 */
public record KeyOwner(boolean group, Long id) {

    public KeyOwner {
        Objects.requireNonNull(id, "key owner id");
    }


    public static KeyOwner user(Long userId) {
        return new KeyOwner(false, userId);
    }


    public static KeyOwner group(Long groupId) {
        return new KeyOwner(true, groupId);
    }


    /** 데이터 key 를 감쌀 때의 AAD 다. 감싼 key 를 다른 주인의 줄로 옮기면 풀리지 않는다 */
    public byte[] wrapBinding() {
        return ("user_data_key:" + (group ? "group:" : "user:") + id).getBytes(StandardCharsets.UTF_8);
    }
}
