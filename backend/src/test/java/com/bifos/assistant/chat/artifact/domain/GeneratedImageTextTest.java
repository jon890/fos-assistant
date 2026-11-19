package com.bifos.assistant.chat.artifact.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 그림 제목 정리와 답 글의 경로 가리기를 확인한다(ADR-20261119 / generated-image). */
class GeneratedImageTextTest {
    private static final Instant NOW = Instant.parse("2026-11-19T05:30:00Z");


    @Test
    @DisplayName("제목은 주소와 경로에 쓸 수 없는 글자를 빼고 code point 60개로 자른다")
    void cleansTitle() {
        assertThat(GeneratedImageTitle.clean("  수채화   고양이. ", NOW)).isEqualTo("수채화 고양이");
        assertThat(GeneratedImageTitle.clean("100% 리얼; ../a/b\\c", NOW)).isEqualTo("100 리얼 abc");
        assertThat(GeneratedImageTitle.clean("😀".repeat(80), NOW)).as("글자가 아닌 것만 남으면 날짜다").isEqualTo("그림 2026-11-19 14-30");
        assertThat(GeneratedImageTitle.clean("가".repeat(80), NOW).codePointCount(0, 60)).isEqualTo(60);
        assertThat(GeneratedImageTitle.clean("가".repeat(80), NOW)).hasSize(60);
        assertThat(GeneratedImageTitle.clean(null, NOW)).isEqualTo("그림 2026-11-19 14-30");
        assertThat(GeneratedImageTitle.ofPath("generated/수채화 고양이 2.png")).isEqualTo("수채화 고양이 2");
    }


    @Test
    @DisplayName("답 글의 MEDIA 태그와 Hermes 이미지 캐시 경로를 빼고 나머지 글은 그대로 둔다")
    void stripsMediaPaths() {
        assertThat(MediaPathMask.strip("필라테스 사진을 만들었습니다.\n\nMEDIA:/srv/x/.hermes/profiles/p/cache/generated/images/a.png"))
            .isEqualTo("필라테스 사진을 만들었습니다.");
        assertThat(MediaPathMask.strip("그림은 /srv/x/cache/generated/images/a.png 에 있어요.\n\n다음 줄"))
            .isEqualTo("그림은 에 있어요.\n\n다음 줄");
        assertThat(MediaPathMask.strip("MEDIA:")).isEmpty();
        assertThat(MediaPathMask.strip("경로가 없는 글 https://example.com/a.png")).isEqualTo("경로가 없는 글 https://example.com/a.png");
        assertThat(MediaPathMask.strip("SOCIAL_MEDIA:on 설정")).as("낱말의 일부는 태그가 아니다").isEqualTo("SOCIAL_MEDIA:on 설정");
        assertThat(MediaPathMask.strip("MEDIA:on 처럼 경로가 아닌 값")).isEqualTo("MEDIA:on 처럼 경로가 아닌 값");
        assertThat(MediaPathMask.strip("https://example.com/cache/generated/images/a.png 를 봐"))
            .as("웹 주소의 일부는 Hermes 경로가 아니다")
            .isEqualTo("https://example.com/cache/generated/images/a.png 를 봐");
        assertThat(MediaPathMask.strip("경로는 `MEDIA:/srv/x/a.png` 다")).isEqualTo("경로는 `` 다");
        assertThat(MediaPathMask.strip("파일 ~/.hermes/cache/generated/images/a.png 끝")).isEqualTo("파일 끝");
        assertThat(MediaPathMask.strip("파일 /cache/generated/images/a.png 끝")).isEqualTo("파일 끝");
        assertThat(MediaPathMask.strip(null)).isNull();
    }
}
