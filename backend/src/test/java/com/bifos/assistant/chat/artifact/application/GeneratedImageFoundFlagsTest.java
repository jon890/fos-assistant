package com.bifos.assistant.chat.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 그림이 참고한 링크마다 「비서가 찾음」 표시를 적고 읽는 규칙이다(설계 agent-found-image 의 DECISION8). */
class GeneratedImageFoundFlagsTest {
    private static final List<String> LINKS = List.of("https://shop.example.com/goods/1", "https://img.example.com/p.png");


    @Test
    @DisplayName("찾은 주소만 1 로 적고, 찾은 주소가 없으면 칸을 비운다")
    void writesOneFlagPerLink() {
        assertThat(GeneratedImageService.foundFlags(LINKS, List.of(LINKS.get(1)))).isEqualTo("01");
        assertThat(GeneratedImageService.foundFlags(LINKS, LINKS)).isEqualTo("11");
        assertThat(GeneratedImageService.foundFlags(LINKS, List.of())).isNull();
        assertThat(GeneratedImageService.foundFlags(LINKS, List.of("https://other.example.com/x.png"))).isNull();
    }


    @Test
    @DisplayName("표시를 같은 순서로 읽고, 옛 행과 수가 다른 행은 표시를 붙이지 않는다")
    void readsFlagsOnlyWhenTheyMatchLinks() {
        assertThat(GeneratedImageService.foundOf(LINKS, "01")).containsExactly(LINKS.get(1));
        assertThat(GeneratedImageService.foundOf(LINKS, null)).as("옛 행").isEmpty();
        assertThat(GeneratedImageService.foundOf(LINKS, "1")).as("링크 수와 다르다").isEmpty();
        assertThat(GeneratedImageService.foundOf(List.of(), "01")).as("링크를 풀지 못했다").isEmpty();
    }
}
