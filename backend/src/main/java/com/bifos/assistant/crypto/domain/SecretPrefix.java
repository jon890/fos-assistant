package com.bifos.assistant.crypto.domain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 비밀값의 앞부분을 화면에 보일지 정한다.
 *
 * <p>값이 {@value #MIN_VALUE_LENGTH}자 이상일 때만 앞 {@value #LENGTH}자를 남긴다. 그보다 짧은 값은 앞부분이 원문의 큰 부분이라
 * 아무것도 남기지 않는다. 플러그인 연결의 비밀 칸과 서버 비밀값이 같은 규칙을 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SecretPrefix {

    static final int LENGTH = 4;
    static final int MIN_VALUE_LENGTH = 16;


    /** 보일 앞부분이다. 값이 비었거나 짧으면 null 이다. 서로게이트 쌍을 가르지 않는다 */
    public static String of(String value) {
        if (value == null || value.length() < MIN_VALUE_LENGTH) {
            return null;
        }
        int end = Character.isHighSurrogate(value.charAt(LENGTH - 1)) ? LENGTH - 1 : LENGTH;
        return value.substring(0, end);
    }
}
