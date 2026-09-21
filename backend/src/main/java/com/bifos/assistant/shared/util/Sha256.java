package com.bifos.assistant.shared.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 임의의 문자열에 붙이는 짧은 지문이다.
 *
 * <p>페르소나 본문처럼 판 번호가 없는 글이 그 사이에 바뀌었는지 보는 데 쓴다. 본문을 되보내지 않고 이
 * 값만 주고받는다.
 */
public final class Sha256 {

    /** 지문으로 남기는 해시 길이. SHA-256 앞부분만 쓴다. */
    private static final int HASH_BYTES = 16;

    private Sha256() {
    }

    /**
     * SHA-256 의 앞 16바이트를 16진수 32글자로 적는다.
     *
     * <p>빈 문자열에도 값을 돌려주고 null 은 빈 문자열과 같게 다룬다. 아직 아무것도 쓰이지 않은 글에도
     * 지문이 있어야 처음 쓰는 요청이 「보지 않고 덮어쓰는 것」과 구분된다.
     */
    public static String hex16(String value) {
        String source = value == null ? "" : value;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(Arrays.copyOf(digest, HASH_BYTES));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
