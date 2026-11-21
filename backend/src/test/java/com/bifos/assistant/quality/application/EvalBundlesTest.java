package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.quality.domain.EvalBundle;
import com.bifos.assistant.quality.domain.EvalBundle.EvalExpect;
import com.bifos.assistant.quality.domain.EvalBundle.EvalQuestion;
import com.bifos.assistant.quality.domain.EvalBundle.EvalTurn;
import com.bifos.assistant.quality.domain.type.CardAction;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class EvalBundlesTest {

    private static EvalBundles load(String location) {
        return new EvalBundles(new PathMatchingResourcePatternResolver(), location);
    }


    @Test
    @DisplayName("운영 묶음 폴더의 서비스 개념 묶음이 읽히고 기본값이 채워진다")
    void loadsProductionBundles() {
        EvalBundle bundle = load("classpath:quality/bundles/").find("service-concepts").orElseThrow();

        assertThat(bundle.title()).isEqualTo("서비스 개념");
        assertThat(bundle.timeoutMinutes()).isEqualTo(10);
        assertThat(bundle.pass().ratio()).isEqualTo(0.85);
        assertThat(bundle.questions()).isNotEmpty().allSatisfy(question -> assertThat(question.turns()).isNotEmpty());
    }


    @ParameterizedTest
    @ValueSource(strings = {"duplicate-question", "unknown-slot", "empty-turns", "zero-timeout", "answer-quality"})
    @DisplayName("틀린 묶음 파일은 파일 이름을 담은 예외로 읽기를 멈춘다")
    void rejectsInvalidBundles(String name) {
        assertThatThrownBy(() -> load("classpath:quality/bad-bundles/" + name + "/"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(name + ".json");
    }


    @Test
    @DisplayName("자리를 채우고, 쓴 자리의 값이 비면 문항을 건너뛴다")
    void fillsSlots() {
        EvalQuestion question = new EvalQuestion("q", "connection", List.of(new EvalTurn("{connectorTitle} 연결해 줘", null, CardAction.APPROVE)),
            new EvalExpect(null, List.of("/connections/{connectorId}"), null, null, null, List.of("바꿨"), null, null), false);

        Optional<EvalQuestion> filled = EvalBundles.fill(question, Map.of("connectorId", "calendar", "connectorTitle", "구글 캘린더"));

        assertThat(filled).isPresent();
        assertThat(filled.get().turns()).singleElement().satisfies(turn -> {
            assertThat(turn.text()).isEqualTo("구글 캘린더 연결해 줘");
            assertThat(turn.card()).isEqualTo(CardAction.APPROVE);
        });
        assertThat(filled.get().expect().linkPrefixes()).containsExactly("/connections/calendar");
        assertThat(filled.get().expect().absent()).containsExactly("바꿨");
        assertThat(EvalBundles.fill(question, Map.of("connectorId", "calendar"))).isEmpty();
    }
}
