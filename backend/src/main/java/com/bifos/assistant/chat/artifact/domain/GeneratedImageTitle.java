package com.bifos.assistant.chat.artifact.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 비서가 만든 그림의 제목이다. 제목은 파일 이름으로 쓰므로 경로와 주소에 안전한 글자만 남긴다(ADR-20261119 / generated-image).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class GeneratedImageTitle {

    static final int MAX_CODE_POINTS = 60;
    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FALLBACK = DateTimeFormatter.ofPattern("'그림' yyyy-MM-dd HH-mm").withZone(HOUSEHOLD_ZONE);


    /**
     * 모델이 붙인 제목을 파일 이름으로 쓸 수 있게 고친다.
     *
     * <p>글자와 숫자, 공백, {@code - _ ( ) ,} 만 남긴다. 주소에서 방화벽이 거절하는 {@code %} 와 {@code ;}, 경로 글자, 제어 문자가 빠진다.
     * 이어진 공백은 하나로 줄이고 앞뒤의 공백과 점을 뗀 뒤 code point 로 자른다. 남는 것이 없으면 만든 날짜와 시각이다.
     */
    public static String clean(String raw, Instant now) {
        StringBuilder kept = new StringBuilder();
        if (raw != null) {
            raw.codePoints()
                .filter(GeneratedImageTitle::allowed)
                .limit(MAX_CODE_POINTS * 4L)
                .forEach(kept::appendCodePoint);
        }
        String collapsed = kept.toString().replaceAll("\\s+", " ").strip();
        int[] points = collapsed.codePoints().limit(MAX_CODE_POINTS).toArray();
        String cut = new String(points, 0, points.length).strip();
        return cut.isEmpty() ? FALLBACK.format(now) : cut;
    }


    /** 결과물 경로에서 제목을 꺼낸다. 폴더와 확장자를 뗀다. */
    public static String ofPath(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }


    private static boolean allowed(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
            || codePoint == ' '
            || codePoint == '-'
            || codePoint == '_'
            || codePoint == '('
            || codePoint == ')'
            || codePoint == ',';
    }
}
