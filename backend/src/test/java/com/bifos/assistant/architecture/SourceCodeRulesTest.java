package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("architecture")
class SourceCodeRulesTest {
    @TempDir
    Path temporary;


    @Test
    @DisplayName("운영 소스의 필드 주입과 엔티티 Lombok 위반은 각각 0개다")
    void productionHasNoViolations() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            var sources = files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
            assertThat(sources).isNotEmpty();
            assertThat(SourceCodeRules.violations(sources)).isEmpty();
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"org.springframework.beans.factory.annotation.Autowired", "org.springframework.beans.factory.annotation.Value",
        "jakarta.inject.Inject", "javax.inject.Inject"})
    @DisplayName("필드 주입은 import와 전체 이름 표기를 모두 거절한다")
    void rejectsFieldInjection(String type) throws Exception {
        String name = type.substring(type.lastIndexOf('.') + 1);
        String arguments = name.equals("Value") ? "(\"${sample}\")" : "";
        assertThat(check("import " + type + "; class Sample { @" + name + arguments + " String value; }"))
            .singleElement().asString().contains("rule-86", "Sample.value");
        assertThat(check("class Sample { @" + type + arguments + " String value; }"))
            .singleElement().asString().contains("rule-86", "Sample.value");
        assertThat(check("import " + type.substring(0, type.lastIndexOf('.')) + ".*; class Outer { class Sample { @"
            + name + arguments + " String value; } }"))
                .singleElement().asString().contains("rule-86", "Sample.value");
    }


    @Test
    @DisplayName("생성자와 파라미터와 메서드의 주입 및 주석과 문자열은 허용한다")
    void acceptsNonFieldInjection() throws Exception {
        assertThat(check("""
            import org.springframework.beans.factory.annotation.*;
            class Sample {
                // @Autowired String ignored;
                String text = "@Inject String ignored;";
                @Autowired Sample(@Value("${sample}") String value) {}
                @Autowired void set(String value) {}
            }
            """)).isEmpty();
        assertThat(check("import example.Value; class Sample { @Value String custom; }")).isEmpty();
    }


    @ParameterizedTest
    @ValueSource(strings = {"Data", "Setter", "EqualsAndHashCode", "ToString"})
    @DisplayName("엔티티의 금지 Lombok은 import와 전체 이름과 wildcard 표기를 모두 거절한다")
    void rejectsEntityLombok(String name) throws Exception {
        for (String source : List.of(
            "import jakarta.persistence.Entity; import lombok." + name + "; @Entity @" + name + " class Sample {}",
            "@jakarta.persistence.Entity @lombok." + name + " class Sample {}",
            "import jakarta.persistence.*; import lombok.*; class Outer { @Entity @" + name + " class Sample {} }")) {
            assertThat(check(source)).singleElement().asString().contains("rule-87", "Sample");
        }
    }


    @Test
    @DisplayName("엔티티 필드의 Setter도 거절한다")
    void rejectsEntityFieldSetter() throws Exception {
        assertThat(check("@jakarta.persistence.Entity class Sample { @lombok.Setter String value; }"))
            .singleElement().asString().contains("rule-87", "Sample.value");
    }


    @Test
    @DisplayName("엔티티의 Getter와 생성자 및 일반 클래스의 Lombok은 허용한다")
    void acceptsAllowedLombok() throws Exception {
        assertThat(check("""
            import jakarta.persistence.Entity;
            import lombok.*;
            @Entity @Getter @RequiredArgsConstructor @NoArgsConstructor(access = AccessLevel.PROTECTED)
            class Sample { String text = "@Data @Setter @EqualsAndHashCode @ToString"; }
            @Data @Setter @EqualsAndHashCode @ToString class Dto {}
            """)).isEmpty();
        assertThat(check("import example.Data; @jakarta.persistence.Entity @Data class Sample {}")).isEmpty();
    }


    private List<String> check(String source) throws Exception {
        Path file = temporary.resolve("Sample.java");
        Files.writeString(file, source);
        return SourceCodeRules.violations(List.of(file));
    }


    @Test
    @DisplayName("해석할 수 없는 소스는 위반 0개로 통과하지 않는다")
    void rejectsMalformedSource() {
        assertThatThrownBy(() -> check("class Sample {")).isInstanceOf(IllegalArgumentException.class);
    }
}
