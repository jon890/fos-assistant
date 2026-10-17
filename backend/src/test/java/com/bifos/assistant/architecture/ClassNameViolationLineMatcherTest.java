package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ClassNameViolationLineMatcherTest {

    private final ClassNameViolationLineMatcher matcher = new ClassNameViolationLineMatcher();


    @Test
    @DisplayName("전체 이름의 패키지만 다른 같은 줄은 같다고 본다")
    void ignoresPackageOfClassNames() {
        assertThat(matcher.matches(
            "com.bifos.assistant.chat.turn.application.ChatService 는 인터페이스다.",
            "com.bifos.assistant.chat.turn.application.port.ChatService 는 인터페이스다.")).isTrue();
    }


    @Test
    @DisplayName("한 줄의 여러 클래스 이름에서 각각 패키지를 지운다")
    void ignoresPackageOfEveryClassNameInLine() {
        assertThat(matcher.matches(
            "Method <com.bifos.assistant.a.infra.Store.save()> calls <com.bifos.assistant.a.application.Service.run()>",
            "Method <com.bifos.assistant.b.infra.Store.save()> calls <com.bifos.assistant.b.application.dto.Service.run()>")).isTrue();
    }


    @Test
    @DisplayName("패키지 루트에 바로 있는 클래스도 단순 이름으로 견준다")
    void handlesClassDirectlyUnderRoot() {
        assertThat(matcher.matches(
            "com.bifos.assistant.LivePropertiesConfig 가 쓴다",
            "com.bifos.assistant.config.LivePropertiesConfig 가 쓴다")).isTrue();
    }


    @Test
    @DisplayName("단순 클래스 이름이 다르면 다른 줄이다")
    void differsWhenSimpleNameDiffers() {
        assertThat(matcher.matches(
            "com.bifos.assistant.chat.turn.application.ChatService 는 인터페이스다.",
            "com.bifos.assistant.chat.application.ChatServices 는 인터페이스다.")).isFalse();
    }


    @Test
    @DisplayName("패키지 이름만 있는 줄은 정확히 같아야 같다")
    void keepsPackageOnlyLinesExact() {
        String line = "com.bifos.assistant.chat.application.model 는 application 의 하위 패키지로 허용되지 않는다.";
        assertThat(matcher.matches(line, line)).isTrue();
        assertThat(matcher.matches(line, line.replace(".chat.", ".connector."))).isFalse();
        assertThat(matcher.matches(line, line.replace("application.model", "application.toolset"))).isFalse();
    }


    @Test
    @DisplayName("줄 번호와 익명 클래스 번호는 기본 matcher 처럼 무시한다")
    void ignoresLineAndGeneratedNumbers() {
        assertThat(matcher.matches(
            "com.bifos.assistant.a.A$1 calls B in (A.java:12)",
            "com.bifos.assistant.b.A$7 calls B in (A.java:98)")).isTrue();
        assertThat(matcher.matches("rule 3 위반", "rule 4 위반")).isFalse();
    }
}
