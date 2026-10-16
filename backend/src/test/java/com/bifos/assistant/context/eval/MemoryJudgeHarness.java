package com.bifos.assistant.context.eval;

import com.bifos.assistant.context.eval.MemoryEvalDataset.JudgeCheck;
import com.bifos.assistant.context.eval.MemoryJudgeReport.CaseResult;
import com.bifos.assistant.context.eval.MemoryJudgeReport.CheckResult;
import com.bifos.assistant.context.eval.MemoryJudgeReport.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 사례마다 기억을 실은 답과 기억 없이 한 답을 받고, 판정 모델에 「예, 아니오」 질문을 물어 기대 답과 맞는지 센다. 방법은
 * {@code docs/features/memory.md} 의 「답 판정」 이 갖는다.
 *
 * <p>호출 수 상한이 예산이다. 사례 하나에 드는 호출(답 둘과 판정 2 × 질문 수)이 남은 예산보다 많으면 그 사례부터 멈춘다. 사례를 반쯤 재지
 * 않는다. 판정 불가는 다시 묻지 않는다. 다시 물으면 예산이 흔들린다.
 */
final class MemoryJudgeHarness {

    /** 판정 지침의 버전이다. 지침 글을 바꾸면 올려서 이전 결과와 견주지 않게 한다. */
    static final String JUDGE_PROMPT_VERSION = "judge-v1";

    static final String JUDGE_INSTRUCTIONS = """
            너는 답 판정자다. 사용자 질문과 어시스턴트의 답, 판정 질문 하나를 받는다.
            답 글만 보고 판정 질문에 답한다. 답에 없는 내용을 짐작하지 않는다.
            첫 줄에 YES 나 NO 만 쓴다. 그 밖의 글은 쓰지 않는다.
            """.stripTrailing();


    /**
     * 사례 하나의 입력이다.
     *
     * @param withMemory 기억을 실은 system 글
     * @param withoutMemory 기억 없는 system 글
     */
    record JudgeInput(String caseId, String question, String withMemory, String withoutMemory, List<JudgeCheck> checks) {

        JudgeInput {
            checks = List.copyOf(checks);
        }
    }


    MemoryJudgeReport run(List<JudgeInput> inputs, ChatModel model, MemoryJudgeSettings settings, int datasetVersion) {
        List<CaseResult> results = new ArrayList<>();
        int calls = 0;
        boolean exhausted = false;
        for (JudgeInput input : inputs) {
            if (input.checks().isEmpty()) {
                continue;
            }
            int needed = 2 + 2 * input.checks().size();
            if (calls + needed > settings.maxCalls()) {
                exhausted = true;
                break;
            }
            String answerWith = model.complete(settings.answerModel(), input.withMemory(), input.question());
            String answerWithout = model.complete(settings.answerModel(), input.withoutMemory(), input.question());
            List<CheckResult> checks = new ArrayList<>();
            for (JudgeCheck check : input.checks()) {
                checks.add(new CheckResult(
                    check.id(),
                    check.expect(),
                    judge(model, settings, input.question(), answerWith, check),
                    judge(model, settings, input.question(), answerWithout, check)));
            }
            calls += needed;
            results.add(new CaseResult(input.caseId(), answerWith, answerWithout, checks));
        }
        return new MemoryJudgeReport(
            datasetVersion,
            JUDGE_PROMPT_VERSION,
            settings.answerModel(),
            settings.judgeModel(),
            settings.maxCalls(),
            calls,
            exhausted,
            results);
    }


    private static Verdict judge(
        ChatModel model, MemoryJudgeSettings settings, String question, String answer, JudgeCheck check) {
        String user = "# 사용자 질문\n" + question + "\n\n# 어시스턴트의 답\n" + answer + "\n\n# 판정 질문\n" + check.question();
        return verdictOf(model.complete(settings.judgeModel(), JUDGE_INSTRUCTIONS, user));
    }


    /** 첫 줄의 앞뒤 공백을 빼고 대문자로 바꿔 {@code YES}, {@code NO} 면 그 값이고 그 밖은 판정 불가다. */
    static Verdict verdictOf(String text) {
        if (text == null) {
            return Verdict.INVALID;
        }
        String first = text.strip().lines().findFirst().orElse("").strip().toUpperCase(Locale.ROOT);
        return switch (first) {
            case "YES" -> Verdict.YES;
            case "NO" -> Verdict.NO;
            default -> Verdict.INVALID;
        };
    }
}
