package com.bifos.assistant.shared.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 도구 실패 글이 두 끝 줄 가운데 하나로 끝나는지 확인한다(docs/features/tool-fallback/spec.md 의 REQ3). */
class ToolFailureTextTest {

    @Test
    @DisplayName("다른 길이 있는 실패는 「다음 방법:」 줄로 끝나고 그 줄을 따로 꺼낼 수 있다")
    void alternativeEndsWithNextLine() {
        ToolFailureText text = ToolFailureText.alternative("고칠 기억을 찾지 못했다. ", "memory_id 없이 새로 남긴다");

        assertThat(text.text()).isEqualTo("고칠 기억을 찾지 못했다.\n다음 방법: memory_id 없이 새로 남긴다");
        assertThat(text.endLine()).isEqualTo("다음 방법: memory_id 없이 새로 남긴다");
    }


    @Test
    @DisplayName("바로 알릴 실패는 「사용자에게 바로 알린다.」 줄로 끝난다")
    void askUserEndsWithAskLine() {
        ToolFailureText text = ToolFailureText.askUser("사용자가 이 내용을 거절했다.");

        assertThat(text.text()).isEqualTo("사용자가 이 내용을 거절했다.\n사용자에게 바로 알린다.");
        assertThat(text.endLine()).isEqualTo(ToolFailureText.ASK_USER);
    }


    @Test
    @DisplayName("다음 방법이 빈 실패는 만들 수 없다")
    void blankNextIsRejected() {
        assertThatThrownBy(() -> ToolFailureText.alternative("실패", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolFailureText.alternative("실패", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
