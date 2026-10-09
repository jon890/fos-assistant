package com.bifos.assistant.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkspaceServiceTest {

    @Test
    @DisplayName("지우기 로그의 경로는 줄을 끊는 제어 문자와 구분자를 이스케이프하고 나머지 글자는 그대로 둔다")
    void escapesControlCharactersForLog() {
        String raw = "보고서/a\nb\r\u0000\u007f\u0085\u009f   c.txt";

        assertThat(WorkspaceService.escapeForLog(raw))
                .isEqualTo("보고서/a\\u000Ab\\u000D\\u0000\\u007F\\u0085\\u009F\\u2028\\u2029 c.txt");
    }
}
