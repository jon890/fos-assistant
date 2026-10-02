package com.bifos.assistant.memory.domain;

/** content 칸에 적는 글과 그것을 암호화한 key 의 id 다. keyId 가 null 이면 평문이다(ADR-054). */
public record StoredContent(String content, String keyId) {

    public static StoredContent plain(String content) {
        return new StoredContent(content, null);
    }

    public boolean sealed() {
        return keyId != null;
    }
}
