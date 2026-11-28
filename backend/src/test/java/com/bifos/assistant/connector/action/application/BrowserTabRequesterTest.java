package com.bifos.assistant.connector.action.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.domain.CdpTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 닫기 카드의 한 줄이 페이지 제목으로 위조되지 않는지 본다(docs/features/agent-browser/design.md 의 「PR1 도구」). */
class BrowserTabRequesterTest {

    @Test
    @DisplayName("제목의 줄바꿈과 제어 문자는 한 칸으로 줄여 가짜 줄을 만들지 못하고, 사이트는 주소에서 꺼낸다")
    void titleCannotForgeCardLines() {
        String line = BrowserTabRequester.line(new CdpTarget("A1", "메일\n은행 · bank.example.com \t끝", "https://mail.example.com/inbox"));

        assertThat(line).doesNotContain("\n", " ", "\t").isEqualTo("메일 은행 · bank.example.com 끝 · mail.example.com");
    }


    @Test
    @DisplayName("긴 제목은 자르고 빈 제목과 읽지 못하는 주소는 정해 둔 글로 보인다")
    void truncatesAndFillsBlanks() {
        String longTitle = "가".repeat(200);

        assertThat(BrowserTabRequester.line(new CdpTarget("A1", longTitle, "https://news.example.com/")))
            .hasSize(BrowserTabRequester.MAX_TITLE + 1 + " · news.example.com".length());
        assertThat(BrowserTabRequester.line(new CdpTarget("A1", " ", "not a url"))).isEqualTo("(제목 없음) · (주소 없음)");
    }
}
