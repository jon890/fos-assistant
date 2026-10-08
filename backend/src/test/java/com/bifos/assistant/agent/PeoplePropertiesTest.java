package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.PeopleProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 첫 로그인의 기본 도구 설정이 켤 수 있는 이름만 받는지 본다. */
class PeoplePropertiesTest {

    @Test
    @DisplayName("주인 등급과 실행 공간 도구는 기본 도구로 받는다")
    void acceptsOwnerTierAndSandboxToolsets() {
        PeopleProperties properties =
                new PeopleProperties(null, null, List.of("web", "terminal", "file", "code_execution"));

        assertThat(properties.defaultToolsets()).containsExactly("web", "terminal", "file", "code_execution");
    }

    @Test
    @DisplayName("설정이 없으면 빈 목록이다")
    void defaultsToEmptyList() {
        assertThat(new PeopleProperties(null, null, null).defaultToolsets()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"session_search", "browser", "computer_use", "cronjob", "memory", "fos-assistant", "nope"})
    @DisplayName("실행 공간 밖의 관리자 등급과 모르는 이름은 기동을 막는다")
    void rejectsOtherNames(String name) {
        assertThatThrownBy(() -> new PeopleProperties(null, null, List.of(name)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
