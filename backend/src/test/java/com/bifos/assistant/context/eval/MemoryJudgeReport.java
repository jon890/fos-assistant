package com.bifos.assistant.context.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 답 판정 결과다. 합성 입력만 썼으므로 답 글을 함께 담는다. key 와 주소는 담지 않는다.
 *
 * @param datasetVersion 사례 묶음의 버전
 * @param promptVersion 판정 지침의 버전
 * @param maxCalls 호출 상한
 * @param calls 쓴 호출 수
 * @param budgetExhausted 상한에 닿아 남은 사례를 재지 않았다
 */
record MemoryJudgeReport(
    int datasetVersion,
    String promptVersion,
    String answerModel,
    String judgeModel,
    int maxCalls,
    int calls,
    boolean budgetExhausted,
    List<CaseResult> cases) {


    static final String HEADLINE = "답 판정: 합성 입력만 썼다. 판정 모델의 판단이고 사람이 확인한 값이 아니다.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    MemoryJudgeReport {
        cases = List.copyOf(cases);
    }


    /** 판정 모델의 답이다. 첫 줄이 YES 나 NO 가 아니면 판정 불가다. */
    enum Verdict {
        YES,
        NO,
        INVALID
    }

    /**
     * 사례 하나의 결과다.
     *
     * @param withMemory 기억을 실은 답
     * @param withoutMemory 기억 없이 한 답
     */
    record CaseResult(String caseId, String withMemory, String withoutMemory, List<CheckResult> checks) {

        CaseResult {
            checks = List.copyOf(checks);
        }
    }

    /**
     * 판정 질문 하나의 결과다.
     *
     * @param expect 기억이 제대로 쓰인 답에서 기대하는 판정
     */
    record CheckResult(String id, boolean expect, Verdict withMemory, Verdict withoutMemory) {

        boolean matchesWithMemory() {
            return matches(withMemory);
        }


        boolean matchesWithoutMemory() {
            return matches(withoutMemory);
        }


        private boolean matches(Verdict verdict) {
            return verdict == (expect ? Verdict.YES : Verdict.NO);
        }
    }


    int checks() {
        return (int)cases.stream().mapToLong(each -> each.checks().size()).sum();
    }


    int matchedWithMemory() {
        return (int)cases.stream().flatMap(each -> each.checks().stream()).filter(CheckResult::matchesWithMemory).count();
    }


    int matchedWithoutMemory() {
        return (int)cases.stream()
            .flatMap(each -> each.checks().stream())
            .filter(CheckResult::matchesWithoutMemory)
            .count();
    }


    int invalid() {
        return (int)cases.stream()
            .flatMap(each -> each.checks().stream())
            .mapToLong(check -> (check.withMemory() == Verdict.INVALID ? 1 : 0)
                + (check.withoutMemory() == Verdict.INVALID ? 1 : 0))
            .sum();
    }


    String markdown() {
        StringBuilder out = new StringBuilder(HEADLINE).append("\n\n");
        out.append("사례 묶음 버전 ").append(datasetVersion).append(", 판정 지침 ").append(promptVersion);
        out.append(", 답 모델 ").append(answerModel).append(", 판정 모델 ").append(judgeModel).append("\n\n");
        out.append("| 지표 | 값 |\n| --- | --- |\n");
        out.append("| 판정 질문 수 | ").append(checks()).append(" |\n");
        out.append("| 기억 있음 일치 | ").append(rate(matchedWithMemory())).append(" |\n");
        out.append("| 기억 없음 일치 | ").append(rate(matchedWithoutMemory())).append(" |\n");
        out.append("| 일치율 차이(있음 - 없음) | ").append(difference()).append(" |\n");
        out.append("| 판정 불가 | ").append(invalid()).append(" |\n");
        out.append("| 쓴 호출 | ").append(calls).append(" / ").append(maxCalls).append(" |\n");
        out.append("| 예산 소진 | ").append(budgetExhausted ? "예" : "아니오").append(" |\n");
        return out.toString();
    }


    String json() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", HEADLINE);
        root.put("datasetVersion", datasetVersion);
        root.put("promptVersion", promptVersion);
        root.put("answerModel", answerModel);
        root.put("judgeModel", judgeModel);
        root.put("checks", checks());
        root.put("matchedWithMemory", matchedWithMemory());
        root.put("matchedWithoutMemory", matchedWithoutMemory());
        root.put("invalid", invalid());
        root.put("calls", calls);
        root.put("maxCalls", maxCalls);
        root.put("budgetExhausted", budgetExhausted);
        root.put("cases", cases);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }


    private String rate(int matched) {
        int total = checks();
        if (total == 0) {
            return "해당 없음";
        }
        return String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * matched / total, matched, total);
    }


    private String difference() {
        int total = checks();
        if (total == 0) {
            return "해당 없음";
        }
        return String.format(Locale.ROOT, "%+.1f%%p", 100.0 * (matchedWithMemory() - matchedWithoutMemory()) / total);
    }
}
