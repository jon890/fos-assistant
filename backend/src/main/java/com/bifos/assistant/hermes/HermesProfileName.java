package com.bifos.assistant.hermes;

import java.util.regex.Pattern;

/**
 * Hermes profile 이름이 규칙에 맞는지 본다.
 *
 * <p>같은 정규식을 key 파일 경로를 만드는 자리와 대시보드 경로에 이름을 넣는 자리가 함께 쓴다. 두 벌을
 * 두면 한쪽만 고쳐져 쓴 자리를 읽는 쪽이 찾지 못한다.
 *
 * <p>참거짓만 내고 던지지 않는다. 부르는 자리마다 던질 오류 코드가 달라서다. key 파일을 읽을 때와 쓸
 * 때가 다른 코드를 쓴다.
 */
public final class HermesProfileName {

    private static final Pattern PROFILE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private HermesProfileName() {
    }

    public static boolean isValid(String name) {
        return name != null && PROFILE_NAME.matcher(name).matches();
    }
}
