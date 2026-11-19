package com.bifos.assistant.chat.artifact.domain;

import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 답 글에서 Hermes 쪽 그림 파일 경로를 가린다(ADR-20261119 / generated-image, 사용자 결정 2026-11-19).
 *
 * <p>Hermes 의 이미지 도구는 모델에게 호스트 경로를 돌려주고, 모델은 그것을 {@code MEDIA:<경로>} 나 경로 그대로 답에 적는다. fos-ctx 가
 * 그 결과를 바꾸기 전에 저장된 답과, hook 이 건너뛰어진 답에 그 경로가 남는다. 홈서버의 디렉터리 구조가 화면에 보이지 않게 내보낼 때 뺀다.
 * 저장된 글은 고치지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MediaPathMask {

    /**
     * {@code MEDIA:} 태그와 그 뒤의 경로, 그리고 태그 없이 적은 Hermes 이미지 캐시 폴더의 경로다. 줄 끝의 공백도 함께 뺀다.
     *
     * <p>태그는 낱말의 일부({@code SOCIAL_MEDIA:})가 아니고 뒤가 경로이거나 비었을 때만 잡는다. 캐시 경로는 웹 주소의 일부가 아닐 때만 잡는다.
     * 화면의 {@code web/src/lib/media-path.ts} 와 같은 규칙이어야 한다.
     */
    private static final Pattern PATH = Pattern.compile(
        "[ \\t]*(?:(?<![\\w])MEDIA:(?:[~/][^\\s`'\"()<>]*|(?=\\s|$))"
            + "|(?<![\\w/.:~-])~?(?:/[^\\s`'\"()<>]*)?/cache/generated/images/[^\\s`'\"()<>]*)");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");


    /** 경로를 뺀 글이다. 경로가 없으면 받은 글 그대로다. */
    public static String strip(String text) {
        if (text == null || (!text.contains("MEDIA:") && !text.contains("/cache/generated/images/"))) {
            return text;
        }
        String stripped = PATH.matcher(text).replaceAll("");
        return BLANK_LINES.matcher(stripped).replaceAll("\n\n").strip();
    }
}
