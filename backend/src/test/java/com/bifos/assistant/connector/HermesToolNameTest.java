package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.domain.HermesToolName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 규칙은 {@code docs/hermes/connector-policy.md} 의 「MCP 도구의 등록 이름」 이다. 대시보드 plugin 의 계산과 같은 값을
 * 내야 하므로 {@code hermes/tests/test_connector_manifest.py} 와 같은 입력을 쓴다.
 */
class HermesToolNameTest {
    /** 대시보드 plugin 의 계산으로 미리 구한 값이다. 입력은 서버 {@code long-server-name}, 도구 {@code tool.} 와 x 60자다. */
    private static final String LONG_NAME = "mcp__long_server_name__tool_xxxxxxxxxxxxxxxxxxxxxxxxxxx_96ece0f7";

    @Test
    @DisplayName("서버 이름과 도구 이름에서 영문자와 숫자와 밑줄 밖의 글자를 밑줄로 바꾼다")
    void replacesCharactersOutsideTheAllowedSetWithUnderscore() {
        assertThat(HermesToolName.of("policy-probe", "write_item")).isEqualTo("mcp__policy_probe__write_item");
        assertThat(HermesToolName.of("demo", "a.b")).isEqualTo("mcp__demo__a_b");
    }

    @Test
    @DisplayName("이은 이름이 64자이면 줄이지 않는다")
    void keepsNameOfExactlyTheLimit() {
        String tool = "t".repeat(64 - "mcp__demo__".length());

        assertThat(HermesToolName.of("demo", tool)).isEqualTo("mcp__demo__" + tool);
    }

    @Test
    @DisplayName("64자를 넘으면 앞 55자에 밑줄과 이름 전체의 SHA-256 앞 8자를 붙여 64자로 줄인다")
    void cutsLongNameWithHash() {
        String name = HermesToolName.of("long-server-name", "tool." + "x".repeat(60));

        assertThat(name).hasSize(64).isEqualTo(LONG_NAME);
    }
}
