package com.bifos.assistant.memory.application.model;

import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code memory_search} 의 다음 페이지 위치다(ADR-20261015 / memory-hybrid-selection 「검색의 페이지」).
 *
 * <p>질문의 지문과 다음 순위만 담는다. 권한을 담지 않으므로 서명하지 않는다. 페이지마다 그때의 권한과 항목으로 다시 점수를 매긴다.
 *
 * @param queryFingerprint 앞뒤 공백을 뺀 질문의 SHA-256 앞 16 hex
 * @param offset 이 페이지가 시작하는 순위. 0 부터 센다
 */
public record MemorySearchCursor(String queryFingerprint, int offset) {

    /** 앞에서 이 순위까지만 읽는다. */
    public static final int MAX_RANK = 200;

    private static final String VERSION = "v1";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern ENCODED = Pattern.compile("v1:([0-9a-f]{16}):([0-9]{1,3})");


    /** 첫 페이지의 위치다. */
    public static MemorySearchCursor first(String strippedQuery) {
        return new MemorySearchCursor(fingerprintOf(strippedQuery), 0);
    }


    public static String fingerprintOf(String strippedQuery) {
        return Sha256.hex(strippedQuery).substring(0, 16);
    }


    /** {@code "v1:<지문>:<순위>"} 의 base64url(padding 없음)이다. */
    public String encode() {
        String plain = VERSION + ":" + queryFingerprint + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }


    /** 해독할 수 없거나 버전이 다르거나 순위가 1 이상 {@link #MAX_RANK} 미만이 아니면 빈 값이다. */
    public static Optional<MemorySearchCursor> parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 200) {
            return Optional.empty();
        }
        String plain;
        try {
            plain = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        var matched = ENCODED.matcher(plain);
        if (!matched.matches()) {
            return Optional.empty();
        }
        int offset = Integer.parseInt(matched.group(2));
        if (offset < 1 || offset >= MAX_RANK) {
            return Optional.empty();
        }
        return Optional.of(new MemorySearchCursor(matched.group(1), offset));
    }


    /** 이 커서가 그 질문의 것인가. */
    public boolean matches(String strippedQuery) {
        return FINGERPRINT.matcher(queryFingerprint).matches() && queryFingerprint.equals(fingerprintOf(strippedQuery));
    }
}
