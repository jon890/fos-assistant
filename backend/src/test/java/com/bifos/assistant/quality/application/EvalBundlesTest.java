package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.quality.application.dto.EvalObservation;
import com.bifos.assistant.quality.domain.EvalBundle;
import com.bifos.assistant.quality.domain.EvalBundle.EvalExpect;
import com.bifos.assistant.quality.domain.EvalBundle.EvalQuestion;
import com.bifos.assistant.quality.domain.EvalBundle.EvalTurn;
import com.bifos.assistant.quality.domain.type.CardAction;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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


    @Test
    @DisplayName("지역 없는 날씨 예약은 지역을 되묻고 예약 생성 도구를 실행하지 않아야 통과한다")
    void judgesWeatherClarification() {
        EvalQuestion question = load("classpath:quality/bundles/").find("service-concepts").orElseThrow().questions().stream()
            .filter(item -> item.id().equals("schedule-weather-clarify")).findFirst().orElseThrow();
        EvalExpect expect = EvalBundles.fill(question, Map.of()).orElseThrow().expect();
        String reply = "어느 지역 날씨를 알려 드릴까요?";

        assertThat(EvalJudge.judge(expect, new EvalObservation(reply, null, null, null)).passed()).isTrue();
        assertThat(EvalJudge.judge(expect, new EvalObservation("네, 알겠습니다.", null, null, null)).passed()).isFalse();
        assertThat(EvalJudge.judge(expect, new EvalObservation(reply, Set.of("task_create"), null, null)).failures())
            .containsExactly("FORBIDDEN_TOOL:task_create");
    }


    @ParameterizedTest
    @ValueSource(strings = {"duplicate-question", "unknown-slot", "empty-turns", "zero-timeout", "answer-quality", "empty-expect", "blank-value",
        "no-tag", "bad-card-status", "missing-attachment", "external-drops-conversation"})
    @DisplayName("틀린 묶음 파일은 파일 이름을 담은 예외로 읽기를 멈춘다")
    void rejectsInvalidBundles(String name) {
        assertThatThrownBy(() -> load("classpath:quality/bad-bundles/" + name + "/"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(name + ".json");
    }


    @Test
    @DisplayName("답변 품질 묶음은 밖에서 채점하므로 기대 없이 읽히고 모든 문항이 대화를 남긴다")
    void answerQualityBundleIsExternal() {
        EvalBundle bundle = load("classpath:quality/bundles/").find("answer-quality").orElseThrow();

        assertThat(bundle.pass().external()).isTrue();
        assertThat(bundle.timeoutMinutes()).isEqualTo(15);
        assertThat(bundle.questions()).isNotEmpty().allMatch(EvalQuestion::keepConversation);
    }


    @Test
    @DisplayName("묶음 파일이 하나도 없으면 읽기를 멈춘다")
    void rejectsEmptyFolder() {
        assertThatThrownBy(() -> load("classpath*:quality/no-such-folder/")).isInstanceOf(IllegalStateException.class).hasMessageContaining("no eval bundles");
    }


    @Test
    @DisplayName("자리를 채우고, 쓴 자리의 값이 비면 문항을 건너뛴다")
    void fillsSlots() {
        EvalQuestion question = new EvalQuestion("q", "connection", List.of(new EvalTurn("{connectorTitle} 연결해 줘", null, CardAction.APPROVE)),
            new EvalExpect(null, List.of("/connections/{connectorId}"), null, null, null, List.of("바꿨"), null, null, null), false);

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
