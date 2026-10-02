package com.bifos.assistant.memory.application;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 민감 Memory 본문을 암호화하는 key 설정이다(ADR-055).
 *
 * <p>둘 다 비우면 암호화가 꺼진다. 기동은 되고 민감 항목의 저장과 수정, 암호화한 줄의 읽기만 거절한다.
 * 한쪽만 비었거나 모양이 틀리면 기동에서 멈춘다. 예외 메시지에 key 값을 넣지 않는다.
 *
 * @param activeKeyId 새로 쓸 때 쓰는 key 의 id
 * @param keys {@code <id>:<base64 32바이트>} 를 쉼표로 이은 목록
 */
@Validated
@ConfigurationProperties(prefix = "assistant.memory.encryption")
public record MemoryEncryptionProperties(String activeKeyId, String keys) {

    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final int KEY_BYTES = 32;

    public MemoryEncryptionProperties {
        activeKeyId = activeKeyId == null ? "" : activeKeyId.strip();
        keys = keys == null ? "" : keys.strip();
        if (activeKeyId.isEmpty() != keys.isEmpty()) {
            throw new IllegalArgumentException("memory encryption: active-key-id 와 keys 는 함께 채우거나 함께 비워야 한다");
        }
        // 모양 검사를 지나지 못한 글은 key 값일 수 있어 메시지에 넣지 않는다
        if (!activeKeyId.isEmpty() && !KEY_ID.matcher(activeKeyId).matches()) {
            throw new IllegalArgumentException("memory encryption: active-key-id 모양이 틀렸다");
        }
        if (!keys.isEmpty() && !parse(keys).containsKey(activeKeyId)) {
            throw new IllegalArgumentException("memory encryption: active-key-id 가 keys 에 없다: " + activeKeyId);
        }
    }

    /** key id 에서 32바이트 key 로 가는 map 이다. 암호화가 꺼져 있으면 비어 있다. */
    public Map<String, byte[]> parsedKeys() {
        return parse(keys);
    }

    private static Map<String, byte[]> parse(String keys) {
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return parsed;
        }
        String[] pieces = keys.split(",");
        for (int index = 0; index < pieces.length; index++) {
            String piece = pieces[index];
            int colon = piece.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("memory encryption: " + (index + 1) + "번째 항목이 <id>:<값> 모양이 아니다");
            }
            String id = piece.substring(0, colon).strip();
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("memory encryption: " + (index + 1) + "번째 항목의 key id 모양이 틀렸다");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(piece.substring(colon + 1).strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("memory encryption: key 값이 base64 가 아니다: " + id);
            }
            if (bytes.length != KEY_BYTES) {
                throw new IllegalArgumentException("memory encryption: key 값이 32바이트가 아니다: " + id);
            }
            if (parsed.put(id, bytes) != null) {
                throw new IllegalArgumentException("memory encryption: key id 가 겹친다: " + id);
            }
        }
        return parsed;
    }

    @Override
    public String toString() {
        return "MemoryEncryptionProperties[activeKeyId=" + activeKeyId + "]";
    }
}
