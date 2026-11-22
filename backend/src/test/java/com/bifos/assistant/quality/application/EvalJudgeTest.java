package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.quality.application.dto.EvalObservation;
import com.bifos.assistant.quality.domain.EvalBundle.EvalExpect;
import com.bifos.assistant.quality.domain.EvalBundle.PassRule;
import com.bifos.assistant.quality.domain.EvalResult.BundleResult;
import com.bifos.assistant.quality.domain.EvalResult.ItemResult;
import com.bifos.assistant.quality.domain.type.EvalItemStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 옛 서비스 개념 평가({@code test/model-eval/concepts/concepts-eval-score.test.ts})의 사례를 옮겨 같은 판정을 내는지 본다. */
class EvalJudgeTest {

    private static EvalExpect expect(
        List<String> links, List<String> prefixes, List<String> noPrefixes, List<String> tools, List<String> includes, List<String> absent) {
        return new EvalExpect(links, prefixes, noPrefixes, tools, includes, absent, null, null);
    }


    private static EvalObservation answer(String text, String... tools) {
        return new EvalObservation(text, Set.of(tools), List.of(), null);
    }


    @Test
    @DisplayName("답의 링크는 마크다운 대상과 맨 주소를 모두 찾고 전체 주소는 경로와 질의 문자열만 남긴다")
    void answerLinksFindsMarkdownAndBarePaths() {
        String answer = String.join("\n",
            "[예약 작업 만들기](/tasks/new) 에서 만들 수 있어요.",
            "연결은 /connections/google-calendar?agent=my-agent 화면에서 해요.",
            "[새 대화](https://assistant.example.com/new?agent=blog) 로 가세요.",
            "주소는 /memory.",
            "파일은 [여기](</files>) 가 아니라 /files 에 있어요.");

        List<String> links = EvalJudge.answerLinks(answer);

        assertThat(links).contains("/tasks/new", "/connections/google-calendar?agent=my-agent", "/new?agent=blog", "/memory", "/files");
        assertThat(links).noneMatch(link -> link.startsWith("/assistant"));
    }


    @Test
    @DisplayName("줄바꿈 없는 공백과 전각 공백은 옛 판정처럼 주소를 끊는다")
    void unicodeSpacesEndBarePaths() {
        assertThat(EvalJudge.answerLinks("주소는 /memory\u00A0입니다. 파일은 /files\u3000에 있어요")).containsExactly("/memory", "/files");
    }


    @Test
    @DisplayName("날짜나 분수 같은 슬래시는 링크로 읽지 않는다")
    void slashesInDatesAreNotLinks() {
        assertThat(EvalJudge.answerLinks("11/20 에 3/4 만큼 했다. and/or")).isEmpty();
    }


    @Test
    @DisplayName("도구는 끝난 Control Plane 도구만 앞부분을 떼어 이름으로 센다")
    void controlPlaneToolsDropsPrefixAndOtherTools() {
        assertThat(EvalJudge.controlPlaneTools(List.of("mcp__fos_assistant__connector_list", "mcp__fos_assistant__agent_list", "web_search")))
            .containsExactlyInAnyOrder("connector_list", "agent_list");
    }


    @Test
    @DisplayName("링크는 똑같음, 앞부분 맞음, 없어야 할 앞부분을 나눠 본다")
    void linkExpectations() {
        EvalExpect exact = expect(List.of("/connections"), null, null, null, null, null);
        assertThat(EvalJudge.judge(exact, answer("[연결](/connections)")).passed()).isTrue();
        assertThat(EvalJudge.judge(exact, answer("[연결](/connections/calendar)")).passed()).isFalse();

        EvalExpect prefix = expect(null, List.of("/connections/calendar"), null, null, null, null);
        assertThat(EvalJudge.judge(prefix, answer("/connections/calendar?agent=a")).passed()).isTrue();

        EvalExpect none = expect(null, null, List.of("/new?agent="), null, null, null);
        assertThat(EvalJudge.judge(none, answer("쓸 수 있는 에이전트는 블로그 비서예요")).passed()).isTrue();
        assertThat(EvalJudge.judge(none, answer("[새 대화](/new?agent=x)")).failures()).containsExactly("FORBIDDEN_LINK:/new?agent=");
    }


    @Test
    @DisplayName("도구, 있어야 할 낱말 하나, 없어야 할 낱말을 모두 보고 미달 까닭을 남긴다")
    void toolsAndWords() {
        EvalExpect target = expect(null, null, null, List.of("connector_list"), List.of("입력창", "모델 단계"), List.of("연결했"));

        assertThat(EvalJudge.judge(target, answer("입력창에서 고르세요", "connector_list")).passed()).isTrue();
        assertThat(EvalJudge.judge(target, answer("연결했어요")).failures())
            .containsExactly("MISSING_TOOL:connector_list", "MISSING_WORD:입력창|모델 단계", "FORBIDDEN_WORD:연결했");
    }


    @Test
    @DisplayName("답이 링크한 예약 작업 번호를 찾고, 지시문 요소는 항목마다 낱말 하나가 있으면 통과다")
    void instructionIncludes() {
        String id = "0192f6a1-0000-7000-8000-000000000001";
        String text = "만들었어요. [아침 날씨](/tasks/" + id + ") 에서 바꿀 수 있어요. 목록은 /tasks 예요.";
        assertThat(EvalJudge.linkedTaskIds(text)).containsExactly(id);

        EvalExpect expect = new EvalExpect(null, null, null, null, null, null, List.of("30만|300000", "없으면|없을 때"), null);
        assertThat(EvalJudge.judge(expect, new EvalObservation(text, Set.of(), List.of("가격이 30만 원 밑이면 알린다. 새 소식이 없으면 알리지 않는다."), null))
            .passed()).isTrue();
        assertThat(EvalJudge.judge(expect, new EvalObservation(text, Set.of(), List.of("가격을 확인해 알린다."), null)).failures())
            .containsExactly("MISSING_INSTRUCTION:30만|300000", "MISSING_INSTRUCTION:없으면|없을 때");
        assertThat(EvalJudge.judge(expect, new EvalObservation(text, Set.of(), List.of(), null)).failures())
            .containsExactly("MISSING_INSTRUCTION:-");
    }


    @Test
    @DisplayName("승인 카드 결과는 마지막으로 누른 줄의 상태가 기대와 같아야 통과다")
    void cardStatus() {
        EvalExpect expect = new EvalExpect(null, null, null, null, null, null, null, "SUCCEEDED");

        assertThat(EvalJudge.judge(expect, new EvalObservation("지웠어요", Set.of(), List.of(), "SUCCEEDED")).passed()).isTrue();
        assertThat(EvalJudge.judge(expect, new EvalObservation("지웠어요", Set.of(), List.of(), "REJECTED")).failures())
            .containsExactly("CARD_STATUS:SUCCEEDED");
        assertThat(EvalJudge.judge(expect, new EvalObservation("지웠어요", Set.of(), List.of(), null)).passed()).isFalse();
    }


    @Test
    @DisplayName("묶음은 돈 문항의 85% 이상(올림)이면 통과이고 건너뛴 문항은 분모에서 빠진다")
    void bundleRatio() {
        PassRule rule = new PassRule(PassRule.RATIO, 0.85);

        assertThat(EvalJudge.bundleResult("b", rule, ran(12, 14)).status()).isEqualTo("PASSED");
        assertThat(EvalJudge.bundleResult("b", rule, ran(11, 14)).status()).isEqualTo("FAILED");
        List<ItemResult> withSkipped = new ArrayList<>(ran(11, 12));
        withSkipped.add(item(EvalItemStatus.SKIPPED, List.of()));
        assertThat(EvalJudge.bundleResult("b", rule, withSkipped)).isEqualTo(new BundleResult("b", "PASSED", 11, 12, 1, 0));
        assertThat(EvalJudge.bundleResult("b", rule, List.of()).status()).isEqualTo("FAILED");
    }


    @Test
    @DisplayName("보내기나 읽기 오류, 시간 초과로 끝난 문항이 하나라도 있으면 비율을 넘어도 통과가 아니다")
    void errorsFailTheBundle() {
        List<ItemResult> items = new ArrayList<>(ran(10, 10));
        items.add(item(EvalItemStatus.TIMED_OUT, List.of()));

        BundleResult result = EvalJudge.bundleResult("b", new PassRule(PassRule.RATIO, 0.85), items);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errors()).isEqualTo(1);
    }


    @Test
    @DisplayName("밖에서 채점하는 묶음은 통과·실패를 정하지 않고 답을 받은 수만 센다")
    void externalBundleOnlyCounts() {
        BundleResult result = EvalJudge.bundleResult("answer-quality", new PassRule(PassRule.EXTERNAL, null), ran(2, 3));

        assertThat(result).isEqualTo(new BundleResult("answer-quality", "EXTERNAL", 2, 3, 0, 0));
    }


    private static List<ItemResult> ran(int passed, int total) {
        List<ItemResult> items = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            items.add(index < passed ? item(EvalItemStatus.PASSED, List.of()) : item(EvalItemStatus.FAILED, List.of("MISSING_TOOL:x")));
        }
        return items;
    }


    private static ItemResult item(EvalItemStatus status, List<String> failures) {
        return new ItemResult("b", "q" + System.nanoTime(), status, null, failures, 0, null, null, null, List.of(), false);
    }
}
