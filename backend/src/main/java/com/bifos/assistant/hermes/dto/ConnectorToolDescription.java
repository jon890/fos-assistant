package com.bifos.assistant.hermes.dto;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 도구 설명의 표시 계약을 검증한다. 정책이나 원문 보호 SHA에는 관여하지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorToolDescription {
    private static final Pattern TRIM = Pattern.compile("^[\\t\\n\\r \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+|[\\t\\n\\r \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+$");


    static String read(String value) {
        if (value == null || value.codePoints().anyMatch(ConnectorToolDescription::forbidden)) {
            return null;
        }
        String trimmed = TRIM.matcher(value).replaceAll("");
        int length = trimmed.codePointCount(0, trimmed.length());
        return length >= 1 && length <= 2000 && trimmed.getBytes(StandardCharsets.UTF_8).length <= 8192
            ? trimmed : null;
    }


    private static boolean forbidden(int point) {
        return (point < 32 && point != 9 && point != 10 && point != 13)
            || (point >= 0x7f && point <= 0x9f) || point == 0x061c || point == 0x200e || point == 0x200f
            || (point >= 0x202a && point <= 0x202e) || (point >= 0x2066 && point <= 0x2069)
            || (point >= 0xd800 && point <= 0xdfff);
    }
}
