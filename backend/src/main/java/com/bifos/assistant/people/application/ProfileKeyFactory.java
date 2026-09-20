package com.bifos.assistant.people.application;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * profile 하나가 쓸 API server key 를 만든다.
 *
 * <p>256비트를 {@link SecureRandom} 으로 뽑아 URL 에 쓸 수 있는 글자로 옮긴다. 이 값은 HTTP 헤더와
 * {@code .env} 한 줄에 그대로 실리므로 따옴표나 줄바꿈이 들어가면 안 된다.
 */
@Component
public class ProfileKeyFactory {

    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
