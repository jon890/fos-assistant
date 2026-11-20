package com.bifos.assistant.crypto.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 서버 비밀값 한 줄의 상태다. 원문은 담지 않는다.
 *
 * @param name 이름
 * @param set 값이 있는가
 * @param prefix 화면에 보일 앞부분. 짧거나 비었으면 null
 * @param meta 비밀이 아닌 부가 값
 * @param updatedBy 마지막으로 바꾼 사용자 번호. 기동 때 가져온 값이면 null
 * @param updatedAt 마지막으로 바꾼 시각
 */
public record ServerSecretRecord(
    String name, boolean set, String prefix, Map<String, Object> meta, Long updatedBy, Instant updatedAt) {

    public ServerSecretRecord {
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
}
