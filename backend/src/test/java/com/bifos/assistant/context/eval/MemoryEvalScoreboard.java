package com.bifos.assistant.context.eval;

import com.bifos.assistant.context.eval.MemoryEvalDataset.Category;
import com.bifos.assistant.context.eval.MemoryEvalDataset.ForbiddenKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사례마다의 조립 판정을 모아 지표와 보고서를 만든다. 지표의 정의는 {@code docs/backend/memory-eval.md} 의 「지표」 가 갖는다.
 *
 * <p>모드마다, 범주마다 센다. 보고서는 모드를 열로 둔다. 분모가 0 인 비율은 숫자 대신 「해당 없음」(JSON 은 {@code null}) 이다.
 */
final class MemoryEvalScoreboard {

    static final String HEADLINE = "합성 측정: 모델 없이 조립 결과로 판정했다. 모델 답의 오기억률이 아니다.";

    private static final String ALL = "전체";
    private static final String NOT_APPLICABLE = "해당 없음";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int datasetVersion;
    private final Map<String, List<CaseVerdict>> verdictsByMode = new LinkedHashMap<>();

    MemoryEvalScoreboard(int datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    /** 조립 결과의 문맥 묶음에서 읽은 항목의 상태다. */
    enum ItemState {
        /** 본문으로 실렸다. */
        INLINE,
        /** 제목만 실렸다. */
        TITLE_ONLY,
        /** 묶음에는 있으나 자리가 없어 빠졌다. */
        OMITTED,
        /** 묶음에 없다. */
        ABSENT
    }

    /**
     * 사례 하나에서 항목 하나의 판정이다.
     *
     * @param memory 사례 안의 Memory key
     * @param forbiddenKind 실리면 안 되는 항목이면 그 종류. {@code expect} 항목이면 null
     * @param state 조립 결과에서 읽은 상태
     */
    record ItemVerdict(String memory, ForbiddenKind forbiddenKind, ItemState state) {

        boolean expected() {
            return forbiddenKind == null;
        }
    }

    /**
     * 사례 하나를 조립한 판정이다.
     *
     * @param chars 조립 결과의 글자 수. 공통 답변 지침은 뺀다
     * @param omittedItems 자리가 없어 빠진 항목 수
     */
    record CaseVerdict(String caseId, Category category, List<ItemVerdict> items, long chars, int omittedItems) {

        CaseVerdict {
            items = List.copyOf(items);
        }
    }

    /**
     * 분자와 분모와 비율이다.
     *
     * @param rate 분모가 0 이면 null
     */
    record Ratio(int numerator, int denominator, Double rate) {

        static Ratio of(int numerator, int denominator) {
            return new Ratio(numerator, denominator, denominator == 0 ? null : (double) numerator / denominator);
        }

        String text() {
            if (rate == null) {
                return NOT_APPLICABLE;
            }
            return String.format(Locale.ROOT, "%.1f%% (%d/%d)", rate * 100, numerator, denominator);
        }
    }

    /** 실행당 Memory 글자 수의 평균과 중앙값과 최댓값이다. */
    record Chars(double average, double median, long max) {}

    /**
     * 한 모드의 한 범주를 센 지표다.
     *
     * @param cases 센 사례 수
     * @param recallInline {@code expect} 항목 가운데 {@code INLINE} 인 비율
     * @param recallTitleOrBetter {@code expect} 항목 가운데 {@code INLINE} 이나 {@code TITLE_ONLY} 인 비율
     * @param falseMemoryExposure {@code SUPERSEDED} 와 {@code DISTRACTOR} 항목 가운데 {@code INLINE} 인 비율
     * @param boundaryExposure {@code BOUNDARY} 항목 가운데 {@code ABSENT} 가 아닌 수. {@code OMITTED} 도 센다
     * @param boundaryItems {@code BOUNDARY} 항목 수
     * @param chars 사례가 없으면 null
     * @param omittedItems 빠진 항목 수의 합
     */
    record Metrics(
            int cases,
            Ratio recallInline,
            Ratio recallTitleOrBetter,
            Ratio falseMemoryExposure,
            int boundaryExposure,
            int boundaryItems,
            Chars chars,
            int omittedItems) {}

    /** 한 모드의 사례 판정을 더한다. 모드는 처음 더한 순서로 보고서의 열이 된다. */
    void add(String mode, CaseVerdict verdict) {
        verdictsByMode.computeIfAbsent(mode, key -> new ArrayList<>()).add(verdict);
    }

    List<String> modes() {
        return List.copyOf(verdictsByMode.keySet());
    }

    /**
     * 모드와 범주의 지표다.
     *
     * @param category null 이면 모든 범주를 센다
     */
    Metrics metrics(String mode, Category category) {
        List<CaseVerdict> verdicts = verdictsByMode.getOrDefault(mode, List.of()).stream()
                .filter(verdict -> category == null || verdict.category() == category)
                .toList();
        List<ItemVerdict> items =
                verdicts.stream().flatMap(verdict -> verdict.items().stream()).toList();
        List<ItemVerdict> expected =
                items.stream().filter(ItemVerdict::expected).toList();
        List<ItemVerdict> falseMemories = items.stream()
                .filter(item -> item.forbiddenKind() == ForbiddenKind.SUPERSEDED
                        || item.forbiddenKind() == ForbiddenKind.DISTRACTOR)
                .toList();
        List<ItemVerdict> boundaries = items.stream()
                .filter(item -> item.forbiddenKind() == ForbiddenKind.BOUNDARY)
                .toList();
        return new Metrics(
                verdicts.size(),
                Ratio.of(count(expected, item -> item.state() == ItemState.INLINE), expected.size()),
                Ratio.of(
                        count(
                                expected,
                                item -> item.state() == ItemState.INLINE || item.state() == ItemState.TITLE_ONLY),
                        expected.size()),
                Ratio.of(count(falseMemories, item -> item.state() == ItemState.INLINE), falseMemories.size()),
                count(boundaries, item -> item.state() != ItemState.ABSENT),
                boundaries.size(),
                charsOf(verdicts),
                verdicts.stream().mapToInt(CaseVerdict::omittedItems).sum());
    }

    /** 권한 경계를 넘어 묶음에 든 항목을 「모드 사례 id 항목 key 상태」 로 적는다. 비면 경계가 지켜진 것이다. */
    List<String> boundaryViolations() {
        List<String> violations = new ArrayList<>();
        verdictsByMode.forEach((mode, verdicts) -> verdicts.forEach(verdict -> verdict.items().stream()
                .filter(item -> item.forbiddenKind() == ForbiddenKind.BOUNDARY && item.state() != ItemState.ABSENT)
                .forEach(item ->
                        violations.add(mode + " " + verdict.caseId() + " " + item.memory() + " " + item.state()))));
        return violations;
    }

    String markdown() {
        StringBuilder out = new StringBuilder(HEADLINE).append("\n\n");
        out.append("시험 세트 판 ").append(datasetVersion).append(", 모드 ");
        out.append(String.join(", ", modes())).append("\n");
        appendSection(out, ALL, null);
        Arrays.stream(Category.values()).forEach(category -> appendSection(out, category.name(), category));
        return out.toString();
    }

    String json() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", HEADLINE);
        root.put("datasetVersion", datasetVersion);
        Map<String, Object> modes = new LinkedHashMap<>();
        for (String mode : modes()) {
            Map<String, Object> byCategory = new LinkedHashMap<>();
            byCategory.put(ALL, metrics(mode, null));
            for (Category category : Category.values()) {
                byCategory.put(category.name(), metrics(mode, category));
            }
            modes.put(mode, byCategory);
        }
        root.put("modes", modes);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private void appendSection(StringBuilder out, String title, Category category) {
        List<String> modes = modes();
        out.append("\n## ").append(title).append("\n\n");
        out.append("| 지표 |");
        modes.forEach(mode -> out.append(' ').append(mode).append(" |"));
        out.append("\n| --- |");
        modes.forEach(mode -> out.append(" --- |"));
        out.append('\n');
        List<Metrics> columns =
                modes.stream().map(mode -> metrics(mode, category)).toList();
        appendRow(out, "사례 수", columns, metrics -> String.valueOf(metrics.cases()));
        appendRow(out, "회수율(본문)", columns, metrics -> metrics.recallInline().text());
        appendRow(
                out,
                "회수율(제목 이상)",
                columns,
                metrics -> metrics.recallTitleOrBetter().text());
        appendRow(
                out,
                "오기억 노출률",
                columns,
                metrics -> metrics.falseMemoryExposure().text());
        appendRow(out, "권한 경계 노출", columns, metrics -> metrics.boundaryExposure() + "/" + metrics.boundaryItems());
        appendRow(
                out,
                "Memory 글자 수 평균",
                columns,
                metrics -> metrics.chars() == null
                        ? NOT_APPLICABLE
                        : String.format(Locale.ROOT, "%.1f", metrics.chars().average()));
        appendRow(
                out,
                "Memory 글자 수 중앙값",
                columns,
                metrics -> metrics.chars() == null
                        ? NOT_APPLICABLE
                        : String.format(Locale.ROOT, "%.1f", metrics.chars().median()));
        appendRow(
                out,
                "Memory 글자 수 최댓값",
                columns,
                metrics -> metrics.chars() == null
                        ? NOT_APPLICABLE
                        : String.valueOf(metrics.chars().max()));
        appendRow(out, "빠진 항목", columns, metrics -> String.valueOf(metrics.omittedItems()));
    }

    private static void appendRow(
            StringBuilder out, String label, List<Metrics> columns, Function<Metrics, String> cell) {
        out.append("| ").append(label).append(" |");
        columns.forEach(metrics -> out.append(' ').append(cell.apply(metrics)).append(" |"));
        out.append('\n');
    }

    private static int count(List<ItemVerdict> items, Predicate<ItemVerdict> condition) {
        return (int) items.stream().filter(condition).count();
    }

    private static Chars charsOf(List<CaseVerdict> verdicts) {
        if (verdicts.isEmpty()) {
            return null;
        }
        long[] sorted = verdicts.stream().mapToLong(CaseVerdict::chars).sorted().toArray();
        double average = Arrays.stream(sorted).average().orElseThrow();
        int middle = sorted.length / 2;
        double median = sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
        return new Chars(average, median, sorted[sorted.length - 1]);
    }
}
