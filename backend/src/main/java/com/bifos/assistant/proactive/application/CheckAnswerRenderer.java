package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.CheckResultBlock;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Finding;
import com.bifos.assistant.proactive.application.model.JudgedFinding;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 검사를 마친 살펴보기 결과를 대화에 남길 Markdown 글 하나로 그린다. 모양은 {@code docs/backend/proactive-check.md} 의
 * 「그리기」 가 갖는다.
 *
 * <p>모델이 쓴 글은 신뢰하지 않는다. 글은 모두 한 줄로 합치고 Markdown 문법 글자를 이스케이프해 링크나 이미지, 제목,
 * 목록이 되지 못하게 한다. 링크는 검사를 통과한 원문 주소 하나로만 만들고, 「참고」 로 내린 발견의 주소는 그리지 않는다.
 * 블록 밖의 모델 글은 이 클래스에 들어오지 않는다.
 */
@Component
public class CheckAnswerRenderer {

    /**
     * 이스케이프할 글자다. 백슬래시, 백틱, 별표, 밑줄, 대괄호와 괄호 두 쌍, {@code #}, {@code !}, {@code <}, {@code >},
     * {@code |} 에 더해 {@code :} 와 {@code .} 를 둔다. 뒤의 둘은 화면의 GFM 이 평문 {@code https://...} 와
     * {@code www.} 를 링크로 바꾸지 못하게 한다. CommonMark 는 ASCII 문장부호의 백슬래시 이스케이프를 받고 화면에는 원래 글자가
     * 보인다.
     */
    static final String MARKDOWN_SPECIALS = "\\`*_[]()#!<>|:.";

    private static final DateTimeFormatter CHECKED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX");
    private static final String ITEM_JOINER = "; ";
    private static final String NO_TITLE = "(제목 없음)";
    private static final String NO_NEW_LINE = "새로 알릴 것은 없어요";

    private static final Map<FindingReason, String> REASON_TEXT = Map.of(
            FindingReason.NO_SOURCE, "원문을 확인하지 못했어요",
            FindingReason.NOT_CHECKED_NOW, "이번에 다시 확인하지 않았어요",
            FindingReason.CLOSED, "이미 마감됐어요",
            FindingReason.STALE, "오래된 소식이에요",
            FindingReason.FRESHNESS_UNKNOWN, "지금도 유효한지 모르겠어요",
            FindingReason.INCOMPLETE, "근거가 부족해요",
            FindingReason.REPEATED, "이미 알린 것이에요");

    /** 결과를 그린다. 빈 절은 그리지 않는다. */
    public String render(CheckResultBlock block, List<JudgedFinding> judged) {
        List<JudgedFinding> fresh =
                judged.stream().filter(each -> each.kind() == FindingKind.NEW).toList();
        List<JudgedFinding> references = judged.stream()
                .filter(each -> each.kind() == FindingKind.REFERENCE)
                .toList();

        List<String> parts = new ArrayList<>();
        addIfPresent(parts, block.summary() == null ? null : escape(block.summary()));
        if (!fresh.isEmpty()) {
            parts.add("**새로 알릴 것**\n\n" + newFindings(fresh));
        } else if (block.questions().isEmpty()) {
            parts.add(NO_NEW_LINE);
        }
        if (!references.isEmpty()) {
            parts.add("**참고 (새 추천이 아니에요)**\n" + referenceLines(references));
        }
        addSection(parts, "물어보고 싶은 것", block.questions());
        addSection(parts, "할 일 후보", block.followUpCandidates());
        addSection(parts, "확인하지 못한 출처", block.sourceFailures());
        return String.join("\n\n", parts);
    }

    private static String newFindings(List<JudgedFinding> fresh) {
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            blocks.add(newFinding(i + 1, fresh.get(i)));
        }
        return String.join("\n", blocks);
    }

    private static String newFinding(int number, JudgedFinding judged) {
        Finding finding = judged.finding();
        List<String> lines = new ArrayList<>();
        String heading = titleOf(finding);
        if (finding.area() != null) {
            heading += " · " + escape(finding.area());
        }
        lines.add(number + ". " + heading);
        addIfPresent(lines, sourceLine(finding, judged));
        addIfPresent(lines, line("중요한 이유", finding.whyItMatters() == null ? null : escape(finding.whyItMatters())));
        addIfPresent(lines, line("사실", joinEscaped(finding.facts())));
        addIfPresent(lines, line("추정", joinEscaped(finding.inferences())));
        addIfPresent(lines, line("아직 모르는 것", joinEscaped(finding.unknowns())));
        addIfPresent(
                lines,
                line("지난번과 달라진 점", finding.changeSinceLast() == null ? null : escape(finding.changeSinceLast())));
        addIfPresent(lines, line("할 일 후보 또는 논의할 질문", escape(finding.next().text())));
        return String.join("\n", lines);
    }

    /** 원문 링크와 확인 시각 줄이다. 시각은 모델이 준 시각대 그대로 적는다. */
    private static String sourceLine(Finding finding, JudgedFinding judged) {
        String url = judged.sourceUrl();
        if (url == null) {
            return null;
        }
        String line = "   - 원문: [" + escape(hostOf(url)) + "](" + linkDestination(url) + ")";
        String checkedAt = formatCheckedAt(finding.checkedAt());
        return checkedAt == null ? line : line + " · 확인 " + checkedAt;
    }

    private static String formatCheckedAt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CHECKED_AT_FORMAT.format(OffsetDateTime.parse(value));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException ex) {
            return url;
        }
    }

    /** 주소 안의 괄호는 링크를 일찍 닫을 수 있어 퍼센트 인코딩한다. 주소가 가리키는 곳은 같다. */
    private static String linkDestination(String url) {
        return url.replace("(", "%28").replace(")", "%29");
    }

    private static String referenceLines(List<JudgedFinding> references) {
        return references.stream()
                .map(each -> "- " + titleOf(each.finding()) + ": " + REASON_TEXT.get(each.reason()))
                .collect(Collectors.joining("\n"));
    }

    /** 참고로 내린 발견은 제목이 없을 수 있다. */
    private static String titleOf(Finding finding) {
        return finding.title() == null ? NO_TITLE : escape(finding.title());
    }

    private static void addSection(List<String> parts, String heading, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        parts.add("**" + heading + "**\n"
                + items.stream().map(item -> "- " + escape(item)).collect(Collectors.joining("\n")));
    }

    private static String line(String label, String value) {
        return value == null ? null : "   - " + label + ": " + value;
    }

    private static String joinEscaped(List<String> items) {
        if (items.isEmpty()) {
            return null;
        }
        return items.stream().map(CheckAnswerRenderer::escape).collect(Collectors.joining(ITEM_JOINER));
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null) {
            target.add(value);
        }
    }

    /**
     * 모델이 쓴 글을 평문으로 만든다. 줄바꿈과 연속 공백을 한 칸으로 합쳐 줄 머리의 문법을 막고, 문법 글자 앞에 백슬래시를
     * 둔다.
     */
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.replaceAll("\\s+", " ").strip().toCharArray()) {
            if (MARKDOWN_SPECIALS.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
