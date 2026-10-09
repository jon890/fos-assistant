package com.bifos.assistant.crypto.domain;

/**
 * 저장할 암호문과 그것을 연 데이터 key 의 번호다.
 *
 * @param content {@code v1.<IV>.<암호문과 태그>}. 두 조각은 채움 문자 없는 base64url 이다
 * @param keyId {@code user_data_key.id}
 */
public record SealedText(String content, Long keyId) {}
