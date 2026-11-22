package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.EvalVerdict;
import com.bifos.assistant.quality.application.dto.EvalObservation;
import com.bifos.assistant.quality.domain.EvalBundle.EvalExpect;
import com.bifos.assistant.quality.domain.EvalBundle.PassRule;
import com.bifos.assistant.quality.domain.EvalResult.BundleResult;
import com.bifos.assistant.quality.domain.EvalResult.ItemResult;
import com.bifos.assistant.quality.domain.type.EvalItemStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 평가 문항의 규칙 판정이다. 네트워크를 쓰지 않는 순수 함수만 둔다(docs/features/agent-quality/design.md 의 「판정」).
 *
 * <p>옛 서비스 개념 평가({@code test/model-eval/concepts/score.ts})와 같은 입력에 같은 결과를 낸다. 실패 까닭은 고정 코드와 대상 이름만 담고
 * 답 본문은 담지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class EvalJudge {

    static final String MISSING_LINK = "MISSING_LINK";
    static final String FORBIDDEN_LINK = "FORBIDDEN_LINK";
    static final String MISSING_TOOL = "MISSING_TOOL";
    static final String MISSING_WORD = "MISSING_WORD";
    static final String FORBIDDEN_WORD = "FORBIDDEN_WORD";
    static final String MISSING_INSTRUCTION = "MISSING_INSTRUCTION";
    static final String CARD_STATUS = "CARD_STATUS";

    private static final String TOOL_PREFIX = "mcp__fos_assistant__";
    /** JavaScript 의 {@code \\s} 와 같은 공백이다. Java 의 {@code \\s} 는 줄바꿈 없는 공백과 전각 공백을 잡지 않는다. */
    private static final String SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
    private static final Pattern MARKDOWN_TARGET = Pattern.compile("\\]\\([" + SPACE + "]*([^)" + SPACE + "]+)[" + SPACE + "]*\\)");
    /** 앞이 글자나 슬래시가 아닌 곳에서 시작하는 서비스 안 주소다. */
    private static final Pattern BARE_PATH = Pattern.compile("(?<![\\w/.:-])(/[A-Za-z][^" + SPACE + ")\\]」』\"'`<>,]*)");
    private static final Pattern ABSOLUTE = Pattern.compile("^https?://[^/" + SPACE + "]+(/[^" + SPACE + "]*)?$");
    private static final Pattern TASK_LINK = Pattern.compile("^/tasks/([0-9a-fA-F-]{36})$");
    private static final Pattern TRAILING_DOTS = Pattern.compile("[.。]+$");


    /** 기대마다 맞는지 보고 어긋난 것을 {@code 코드:대상} 으로 모은다. */
    static EvalVerdict judge(EvalExpect expect, EvalObservation observation) {
        String answer = observation.answer();
        List<String> links = answerLinks(answer);
        List<String> failures = new ArrayList<>();
        for (String link : expect.links()) {
            if (!links.contains(link)) {
                failures.add(MISSING_LINK + ":" + link);
            }
        }
        for (String prefix : expect.linkPrefixes()) {
            if (links.stream().noneMatch(link -> link.startsWith(prefix))) {
                failures.add(MISSING_LINK + ":" + prefix);
            }
        }
        for (String prefix : expect.noLinkPrefixes()) {
            if (links.stream().anyMatch(link -> link.startsWith(prefix))) {
                failures.add(FORBIDDEN_LINK + ":" + prefix);
            }
        }
        for (String tool : expect.tools()) {
            if (!observation.completedTools().contains(tool)) {
                failures.add(MISSING_TOOL + ":" + tool);
            }
        }
        if (!expect.includes().isEmpty() && expect.includes().stream().noneMatch(answer::contains)) {
            failures.add(MISSING_WORD + ":" + String.join("|", expect.includes()));
        }
        for (String word : expect.absent()) {
            if (answer.contains(word)) {
                failures.add(FORBIDDEN_WORD + ":" + word);
            }
        }
        if (!expect.instructionIncludes().isEmpty()) {
            if (observation.instructions().isEmpty()) {
                failures.add(MISSING_INSTRUCTION + ":-");
            } else {
                for (String item : expect.instructionIncludes()) {
                    List<String> words = List.of(item.split("\\|", -1));
                    boolean found = observation.instructions().stream().anyMatch(text -> words.stream().anyMatch(text::contains));
                    if (!found) {
                        failures.add(MISSING_INSTRUCTION + ":" + item);
                    }
                }
            }
        }
        if (expect.cardStatus() != null && !expect.cardStatus().equals(observation.cardStatus())) {
            failures.add(CARD_STATUS + ":" + expect.cardStatus());
        }
        return new EvalVerdict(failures.isEmpty(), failures);
    }


    /** 답에 든 서비스 안 주소다. 마크다운 링크의 대상과 글 속의 맨 주소를 모두 본다. 전체 주소는 경로와 질의 문자열만 남긴다. */
    static List<String> answerLinks(String answer) {
        Set<String> found = new LinkedHashSet<>();
        Matcher markdown = MARKDOWN_TARGET.matcher(answer);
        while (markdown.find()) {
            String path = normalize(markdown.group(1));
            if (path != null) {
                found.add(path);
            }
        }
        Matcher bare = BARE_PATH.matcher(MARKDOWN_TARGET.matcher(answer).replaceAll("]"));
        while (bare.find()) {
            found.add(TRAILING_DOTS.matcher(bare.group(1)).replaceAll(""));
        }
        return List.copyOf(found);
    }


    /** 답이 링크한 예약 작업의 공개 번호다. 그 지시문을 읽어 {@code instructionIncludes} 를 본다. */
    static List<String> linkedTaskIds(String answer) {
        List<String> ids = new ArrayList<>();
        for (String link : answerLinks(answer)) {
            Matcher matcher = TASK_LINK.matcher(link);
            if (matcher.matches()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }


    /** 끝난 도구 사건의 이름에서 Control Plane 도구 이름만 뗀다. 다른 도구는 뺀다. */
    static Set<String> controlPlaneTools(Collection<String> completedToolNames) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : completedToolNames) {
            if (name != null && name.startsWith(TOOL_PREFIX)) {
                names.add(name.substring(TOOL_PREFIX.length()));
            }
        }
        return names;
    }


    /**
     * 묶음 결과다. 건너뛴 문항은 분모에서 빼고, 돈 문항 수에 비율을 곱해 올린 수 이상 통과하면 통과다. 보내기·읽기 오류나 시간 초과로 끝난 문항이 하나라도
     * 있으면 옛 평가의 오류처럼 통과로 보지 않는다.
     */
    static BundleResult bundleResult(String bundleId, PassRule rule, List<ItemResult> items) {
        List<ItemResult> ran = items.stream().filter(item -> item.status() != EvalItemStatus.SKIPPED).toList();
        int passed = (int)ran.stream().filter(item -> item.status() == EvalItemStatus.PASSED).count();
        int errors = (int)ran.stream().filter(EvalJudge::isError).count();
        int needed = (int)Math.ceil(ran.size() * rule.ratio() - 1e-9);
        boolean ok = errors == 0 && !ran.isEmpty() && passed >= needed;
        return new BundleResult(bundleId, ok ? "PASSED" : "FAILED", passed, ran.size(), items.size() - ran.size(), errors);
    }


    /** 판정까지 가지 못한 문항이다. 규칙 미달({@code FAILED} 에 까닭 목록이 있는 것)은 오류가 아니다. */
    static boolean isError(ItemResult item) {
        return item.status() == EvalItemStatus.TIMED_OUT
            || item.status() == EvalItemStatus.RUNNING
            || (item.status() == EvalItemStatus.FAILED && item.failures().isEmpty());
    }


    private static String normalize(String target) {
        Matcher absolute = ABSOLUTE.matcher(target);
        if (absolute.matches()) {
            return absolute.group(1) == null ? "/" : absolute.group(1);
        }
        return target.startsWith("/") ? target : null;
    }
}
