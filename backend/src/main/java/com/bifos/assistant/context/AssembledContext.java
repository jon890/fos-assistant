package com.bifos.assistant.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * 조립한 문자열과 그 길이, 그리고 자리가 없어 싣지 못한 항목의 번호다.
 *
 * <p>길이와 지문을 실행 기록에 남긴다. 빠진 항목은 번호로 들고 있어 화면이 그 항목에 표시를 달 수
 * 있다.
 */
public record AssembledContext(String instructions, long chars, List<Long> omittedMemoryIds) {

    /** 지문으로 남기는 해시 길이. SHA-256 앞부분만 쓴다. */
    private static final int HASH_BYTES = 16;

    public AssembledContext {
        omittedMemoryIds = omittedMemoryIds == null ? List.of() : List.copyOf(omittedMemoryIds);
    }

    /** 빠진 항목이 하나도 없는 문맥이다. */
    public AssembledContext(String instructions, long chars) {
        this(instructions, chars, List.of());
    }

    /** 넣을 항목이 없으면 빈 문자열 대신 null 을 보낸다. */
    public static AssembledContext empty() {
        return new AssembledContext(null, 0, List.of());
    }

    /**
     * 자리가 없어 싣지 못한 항목 수다.
     *
     * <p>실행 기록의 {@code context_omitted_items} 에 이 값을 적는다. 0 보다 크면 그 실행은 볼 수
     * 있는 Memory 를 전부 받지 못했다는 뜻이다.
     */
    public int omittedItems() {
        return omittedMemoryIds.size();
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
