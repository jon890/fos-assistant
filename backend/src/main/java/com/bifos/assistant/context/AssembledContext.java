package com.bifos.assistant.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** 조립한 문자열과 그 길이. 길이와 지문을 실행 기록에 남긴다. */
public record AssembledContext(String instructions, long chars) {

    /** 지문으로 남기는 해시 길이. SHA-256 앞부분만 쓴다. */
    private static final int HASH_BYTES = 16;

    /** 넣을 항목이 없으면 빈 문자열 대신 null 을 보낸다. */
    public static AssembledContext empty() {
        return new AssembledContext(null, 0);
    }

    /**
     * 실행 기록에 남길 지문이다. SHA-256 의 앞 16바이트를 16진수 32글자로 적는다.
     *
     * <p>본문에 개인 Memory 가 들어 있어 본문 대신 이 값만 남긴다. 같은 문맥은 같은 값을, 다른 문맥은
     * 다른 값을 낸다.
     *
     * <p>넣은 문맥이 없으면 null 이다. 빈 문자열의 해시는 언제나 같은 값이라, 그것을 적으면 문맥 없이
     * 돈 실행이 모두 한 지문으로 묶여 잘못 읽힌다.
     */
    public String instructionsHash() {
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(instructions.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(Arrays.copyOf(digest, HASH_BYTES));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
