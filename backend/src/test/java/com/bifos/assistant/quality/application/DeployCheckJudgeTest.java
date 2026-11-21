package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.quality.application.dto.CheckObservation;
import com.bifos.assistant.quality.application.dto.ChildObservation;
import com.bifos.assistant.quality.application.dto.FeatureVerdict;
import com.bifos.assistant.quality.application.dto.ToolObservation;
import com.bifos.assistant.quality.application.dto.TurnObservation;
import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeployCheckJudgeTest {

    private final DeployCheckJudge judge = new DeployCheckJudge();


    private static TurnObservation turn(String answer, List<ToolObservation> tools) {
        return new TurnObservation(answer, "SUCCEEDED", null, tools, List.of(), List.of(), false);
    }


    private static CheckObservation of(TurnObservation... turns) {
        return new CheckObservation(List.of(turns), 0, false, null, null, false);
    }


    private static ToolObservation ok(String name) {
        return new ToolObservation(name, false);
    }


    private static void assertVerdict(FeatureVerdict verdict, FeatureStatus status, String reason) {
        assertThat(verdict.status()).isEqualTo(status);
        assertThat(verdict.reasonCode()).isEqualTo(reason);
    }


    @Test
    @DisplayName("검색은 web_search 가 끝나고 출처 절에 링크가 있어야 통과다")
    void search() {
        String answer = "답이다.\n\n## 출처\n- [기관](https://example.org/a)";
        assertVerdict(judge.judge(QualityFeature.SEARCH, of(turn(answer, List.of(ok("web_search"))))), FeatureStatus.PASSED, null);
        assertVerdict(judge.judge(QualityFeature.SEARCH, of(turn(answer, List.of()))), FeatureStatus.FAILED, "NO_SEARCH");
        assertVerdict(
            judge.judge(QualityFeature.SEARCH, of(turn(answer, List.of(new ToolObservation("web_search", true))))),
            FeatureStatus.FAILED,
            "NO_SEARCH");
        assertVerdict(
            judge.judge(QualityFeature.SEARCH, of(turn("본문의 [링크](https://example.org/a) 만 있다", List.of(ok("web_search"))))),
            FeatureStatus.FAILED,
            "NO_SOURCES");
    }


    @Test
    @DisplayName("위임은 성공한 위임 자식이 있고 결과가 전해져야 통과다")
    void delegation() {
        TurnObservation withChild = new TurnObservation(
            "맡겼다", "SUCCEEDED", null, List.of(), List.of(new ChildObservation(true, "SUCCEEDED")), List.of(), false);
        TurnObservation failedChild = new TurnObservation(
            "맡겼다", "SUCCEEDED", null, List.of(), List.of(new ChildObservation(true, "FAILED")), List.of(), false);
        TurnObservation nativeOnly = new TurnObservation(
            "맡겼다", "SUCCEEDED", null, List.of(), List.of(new ChildObservation(false, "SUCCEEDED")), List.of(), false);

        assertVerdict(
            judge.judge(QualityFeature.DELEGATION, new CheckObservation(List.of(withChild, turn("결과", List.of())), 0, true, null, null, false)),
            FeatureStatus.PASSED,
            null);
        assertVerdict(
            judge.judge(QualityFeature.DELEGATION, new CheckObservation(List.of(withChild), 0, false, null, null, false)),
            FeatureStatus.FAILED,
            "NOT_DELIVERED");
        assertVerdict(
            judge.judge(QualityFeature.DELEGATION, new CheckObservation(List.of(failedChild), 0, true, null, null, false)),
            FeatureStatus.FAILED,
            "CHILD_FAILED");
        assertVerdict(
            judge.judge(QualityFeature.DELEGATION, new CheckObservation(List.of(nativeOnly), 0, true, null, null, false)),
            FeatureStatus.FAILED,
            "NO_CHILD");
    }


    @ParameterizedTest
    @ValueSource(strings = {"file_space_put", "mcp__fos_assistant__file_space_put"})
    @DisplayName("파일 넣기는 file_space_put 이 끝나고 보관 줄이 생겨야 통과다")
    void filePut(String toolName) {
        TurnObservation put = turn("넣었다", List.of(ok(toolName)));
        assertVerdict(
            judge.judge(QualityFeature.FILE_PUT, new CheckObservation(List.of(put), 1, false, null, null, false)),
            FeatureStatus.PASSED,
            null);
        assertVerdict(
            judge.judge(QualityFeature.FILE_PUT, new CheckObservation(List.of(put), 0, false, null, null, false)),
            FeatureStatus.FAILED,
            "NO_VAULT_ROW");
    }


    @Test
    @DisplayName("그림은 답에 그림 결과물이 묶여야 통과다")
    void image() {
        TurnObservation withImage = new TurnObservation("그렸다", "SUCCEEDED", null, List.of(), List.of(), List.of("IMAGE"), false);
        TurnObservation withDocument = new TurnObservation("썼다", "SUCCEEDED", null, List.of(), List.of(), List.of("DOCUMENT"), false);
        assertVerdict(judge.judge(QualityFeature.IMAGE, of(withImage)), FeatureStatus.PASSED, null);
        assertVerdict(judge.judge(QualityFeature.IMAGE, of(withDocument)), FeatureStatus.FAILED, "NO_IMAGE");
    }


    @Test
    @DisplayName("기억 읽기는 미리 넣은 사실의 낱말이 답에 있어야 통과다")
    void memoryRead() {
        assertVerdict(judge.judge(QualityFeature.MEMORY_READ, of(turn("청록을 좋아하세요.", List.of()))), FeatureStatus.PASSED, null);
        assertVerdict(judge.judge(QualityFeature.MEMORY_READ, of(turn("모르겠어요.", List.of()))), FeatureStatus.FAILED, "FACT_MISSING");
    }


    @Test
    @DisplayName("예약 작업 발화는 발화가 성공하고 실행 번호가 있어야 통과다")
    void taskFire() {
        assertVerdict(
            judge.judge(QualityFeature.TASK_FIRE, new CheckObservation(List.of(), 0, false, "SUCCEEDED", 31L, false)),
            FeatureStatus.PASSED,
            null);
        assertVerdict(
            judge.judge(QualityFeature.TASK_FIRE, new CheckObservation(List.of(), 0, false, "SUCCEEDED", null, false)),
            FeatureStatus.FAILED,
            "TASK_NOT_SUCCEEDED");
        assertVerdict(
            judge.judge(QualityFeature.TASK_FIRE, new CheckObservation(List.of(), 0, false, "SKIPPED", null, false)),
            FeatureStatus.FAILED,
            "TASK_NOT_SUCCEEDED");
    }


    @ParameterizedTest
    @ValueSource(strings = {"attachment_inspect", "mcp__fos_assistant__attachment_inspect"})
    @DisplayName("사진 조회는 묻는 turn 이 원본 조회 도구를 끝내고 심은 글자를 답해야 통과다")
    void photoInspect(String toolName) {
        TurnObservation upload = turn("받았어요", List.of());
        assertVerdict(
            judge.judge(QualityFeature.PHOTO_INSPECT, of(upload, turn("글자는 QX-7319 입니다", List.of(ok(toolName)))),
            FeatureStatus.PASSED,
            null);
        assertVerdict(
            judge.judge(QualityFeature.PHOTO_INSPECT, of(upload, turn("글자는 QX-7319 입니다", List.of()))),
            FeatureStatus.FAILED,
            "NO_INSPECT");
        assertVerdict(
            judge.judge(QualityFeature.PHOTO_INSPECT, of(upload, turn("읽지 못했어요", List.of(ok(toolName)))),
            FeatureStatus.FAILED,
            "MARKER_MISSING");
    }


    @Test
    @DisplayName("시간 초과, Hermes 기다림 초과, 바쁨, 실행 실패, 승인 카드는 기능 규칙보다 먼저 판정한다")
    void commonFailuresComeFirst() {
        TurnObservation passing = turn("청록", List.of());
        TurnObservation failedRun = new TurnObservation("청록", "FAILED", "REMOTE_RUN_LOST", List.of(), List.of(), List.of(), false);
        TurnObservation hermesTimeout = new TurnObservation("", "FAILED", "HERMES_RUN_TIMEOUT", List.of(), List.of(), List.of(), false);
        TurnObservation busy = new TurnObservation("", "FAILED", "USER_BUSY", List.of(), List.of(), List.of(), false);
        TurnObservation approval = new TurnObservation("청록", "SUCCEEDED", null, List.of(), List.of(), List.of(), true);

        assertVerdict(
            judge.judge(QualityFeature.MEMORY_READ, new CheckObservation(List.of(passing), 0, false, null, null, true)),
            FeatureStatus.TIMED_OUT,
            "TIMED_OUT");
        assertVerdict(judge.judge(QualityFeature.MEMORY_READ, of(hermesTimeout)), FeatureStatus.TIMED_OUT, "HERMES_TIMEOUT");
        assertVerdict(judge.judge(QualityFeature.MEMORY_READ, of(busy)), FeatureStatus.FAILED, "BUSY");
        assertVerdict(judge.judge(QualityFeature.MEMORY_READ, of(passing, failedRun)), FeatureStatus.FAILED, "RUN_FAILED");
        assertVerdict(judge.judge(QualityFeature.MEMORY_READ, of(approval)), FeatureStatus.FAILED, "APPROVAL_REQUESTED");
    }


    @Test
    @DisplayName("출처 절에서 마크다운 링크와 맨 주소를 뽑고 중복은 한 번만 센다")
    void sourceLinksDeduplicates() {
        String answer = String.join(
            "\n",
            "환율은 1,400원이다([한국은행](https://example.org/a)).",
            "",
            "## 출처",
            "- [한국은행](https://example.org/a)",
            "- 통계청: https://example.org/b.",
            "- [한국은행 다시](https://example.org/a)");

        assertThat(DeployCheckJudge.sourceLinks(answer)).containsExactly("https://example.org/a", "https://example.org/b");
    }


    @Test
    @DisplayName("출처 절의 머리는 제목 줄, 굵은 글, 콜론 꼴을 모두 받는다")
    void sourceHeadings() {
        List<String> headings = List.of(
            "## 출처", "### 출처", "**출처**", "출처:", "**출처:**", "**출처**:", "## 출처 목록", "### 참고 출처", "## 📚 출처", "- 출처",
            "## 출처 (2026-11-18 기준)", "출처 목록");
        for (String heading : headings) {
            assertThat(DeployCheckJudge.sourceLinks("본문\n\n" + heading + "\n- https://example.org/x"))
                .as(heading)
                .containsExactly("https://example.org/x");
        }
    }


    @Test
    @DisplayName("출처 머리와 같은 줄의 링크도 세고, 출처로 시작하는 보통 문장은 머리로 보지 않는다")
    void sourceSameLineAndSentences() {
        assertThat(DeployCheckJudge.sourceLinks("본문\n\n출처: [한국은행](https://example.org/a)")).containsExactly("https://example.org/a");
        assertThat(DeployCheckJudge.sourceLinks("본문\n\n**출처:** [한국은행](https://example.org/a), https://example.org/b"))
            .containsExactly("https://example.org/a", "https://example.org/b");
        assertThat(DeployCheckJudge.sourceLinks("출처는 따로 없지만 https://example.org/a 를 참고했다.")).isEmpty();
        assertThat(DeployCheckJudge.sourceLinks("출처를 확인하지 못했다.\nhttps://example.org/a")).isEmpty();
    }


    @Test
    @DisplayName("주소 안의 괄호를 자르지 않고 다음 소제목이나 제목 뒤의 링크는 세지 않는다")
    void sourceStopsAtNextHeading() {
        assertThat(DeployCheckJudge.sourceLinks("## 출처\n- [위키](https://example.org/A_(B))\n\n**덧붙임**\n- https://example.org/c"))
            .containsExactly("https://example.org/A_(B)");
        assertThat(DeployCheckJudge.sourceLinks("## 출처\n- https://example.org/a\n\n## 덧붙임\n- https://example.org/b"))
            .containsExactly("https://example.org/a");
        assertThat(DeployCheckJudge.sourceLinks("환율은 [여기](https://example.org/a) 를 본다.\n출처는 없다.")).isEmpty();
    }


    @Test
    @DisplayName("줄바꿈 없는 공백과 전각 공백이 든 출처 머리도 받는다")
    void sourceHeadingsWithUnicodeSpaces() {
        assertThat(DeployCheckJudge.sourceLinks("본문\n\n##\u00a0출처\n- https://example.org/x")).containsExactly("https://example.org/x");
        assertThat(DeployCheckJudge.sourceLinks("본문\n\n출처\u3000목록\n- https://example.org/y")).containsExactly("https://example.org/y");
    }
}
