package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.CheckResultBlock;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Finding;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.Next;
import com.bifos.assistant.proactive.application.model.CheckResultRead;
import com.bifos.assistant.proactive.domain.type.CheckInvalidReason;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 살펴보기 답 끝의 {@code <fos-check-result>} 블록을 읽는다. 형식은 ADR-081 과 {@code docs/backend/proactive-check.md}
 * 의 「결과 계약」 이 갖는다.
 *
 * <p>상한을 넘는 글은 잘라 읽고 넘는 배열 원소는 버린다. 블록을 읽지 못한 것으로 보는 경우는 답이 비었거나, 블록이 없거나,
 * JSON 이 아니거나, {@code version} 이 1이나 2가 아니거나, {@code outcome} 이 없거나 모르는 값일 때뿐이고 그 까닭을
 * {@link CheckInvalidReason} 으로 돌려준다. 읽지 못해도 예외를 밖으로 던지지 않는다.
 *
 * <p>태그 글자 사이에 낀 보이지 않는 서식 문자(Unicode {@code Cf}. 폭 없는 공백, U+FEFF 등)는 무시한다. 모델이 여는 태그 가운데에
 * U+FEFF 를 끼워 낸 답이 운영에서 블록 없음으로 떨어진 적이 있다. 태그 밖의 글은 그대로 검사한다.
 */
@Component
public class CheckResultParser {

    public static final String OPEN_TAG = "<fos-check-result>";
    public static final String CLOSE_TAG = "</fos-check-result>";

    /** JSON 객체 뒤에 남는 글이 있으면 읽지 않는다. 여는 태그를 넓혀 볼 때 두 블록에 걸친 범위를 받지 않게 한다. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Pattern LOOSE_OPEN_TAG = looseTag(OPEN_TAG);
    private static final Pattern LOOSE_CLOSE_TAG = looseTag(CLOSE_TAG);

    private static final String CODE_FENCE = "```";

    private static final int SUMMARY_MAX = 300;
    private static final int QUESTION_MAX = 300;
    private static final int FOLLOW_UP_MAX = 200;
    private static final int SOURCE_FAILURE_MAX = 200;
    private static final int FINDINGS_MAX = 5;
    private static final int QUESTIONS_MAX = 3;
    private static final int FOLLOW_UPS_MAX = 3;
    private static final int SOURCE_FAILURES_MAX = 5;
    private static final int REPORT_CHANGED_MAX = 3;
    private static final int REPORT_DONE_MAX = 3;
    private static final int REPORT_NEXT_MAX = 2;
    private static final int REPORT_LINE_MAX = 300;

    private static final int AREA_MAX = 40;
    private static final int TOPIC_KEY_MAX = 120;
    private static final int TITLE_MAX = 120;
    private static final int SOURCE_URL_MAX = 2000;
    private static final int WHY_IT_MATTERS_MAX = 600;
    private static final int ITEM_MAX = 300;
    private static final int ITEMS_MAX = 6;
    private static final int NEXT_TEXT_MAX = 300;
    private static final int CHANGE_SINCE_LAST_MAX = 300;
    /** 계약에 상한이 없는 짧은 칸(시각, 신선도, next 종류)이 끝없이 길어지지 않게 하는 값이다. */
    private static final int SHORT_FIELD_MAX = 64;

    /**
     * 마지막 블록을 읽는다. 블록이 없거나 읽지 못하면 그 까닭을 돌려준다.
     *
     * <p>마지막 닫는 태그를 먼저 찾고 그 앞의 가장 가까운 여는 태그를 고른다. 블록 뒤의 모델 글이 여는 태그를 말해도 블록을 읽는다.
     * 그 사이가 JSON 객체 하나가 아니면 그 앞의 여는 태그로 하나씩 넓혀 본다. JSON 문자열 값이 여는 태그 글을 담아도 블록을 읽기
     * 위해서다. 넓힌 범위에 앞 블록의 닫는 태그가 들면 JSON 뒤에 남는 글이 있어 읽지 않으므로, 마지막 블록이 깨졌을 때 앞 블록을 대신
     * 읽지 않는다.
     */
    public CheckResultRead read(String answer) {
        if (answer == null || answer.isBlank()) {
            return CheckResultRead.invalid(CheckInvalidReason.EMPTY_ANSWER);
        }
        String text = canonicalTags(answer);
        int close = text.lastIndexOf(CLOSE_TAG);
        int open = close < 0 ? -1 : text.lastIndexOf(OPEN_TAG, close - OPEN_TAG.length());
        if (open < 0) {
            return CheckResultRead.invalid(CheckInvalidReason.NO_BLOCK);
        }
        for (; open >= 0; open = text.lastIndexOf(OPEN_TAG, open - 1)) {
            JsonNode root = objectOf(text.substring(open + OPEN_TAG.length(), close));
            if (root != null) {
                return blockOf(root);
            }
        }
        return CheckResultRead.invalid(CheckInvalidReason.NOT_JSON);
    }

    /** 태그 글자 사이에 서식 문자가 몇 개든 끼어도 맞는 패턴이다. */
    private static Pattern looseTag(String tag) {
        return Pattern.compile(tag.chars()
                .mapToObj(ch -> Pattern.quote(Character.toString(ch)))
                .collect(Collectors.joining("\\p{Cf}*")));
    }

    /** 서식 문자가 낀 태그를 원래 태그로 바꾼다. 태그 밖의 글은 건드리지 않는다. */
    private static String canonicalTags(String answer) {
        String opened = LOOSE_OPEN_TAG.matcher(answer).replaceAll(Matcher.quoteReplacement(OPEN_TAG));
        return LOOSE_CLOSE_TAG.matcher(opened).replaceAll(Matcher.quoteReplacement(CLOSE_TAG));
    }

    /** 태그 사이의 글이 JSON 객체 하나이면 그 객체다. 아니면 null 이다. */
    private static JsonNode objectOf(String between) {
        String body = stripFence(between.strip());
        try {
            JsonNode root = JSON.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JacksonException ex) {
            return null;
        }
    }

    /** 읽은 JSON 객체를 계약대로 검사해 블록으로 바꾼다. */
    private static CheckResultRead blockOf(JsonNode root) {
        JsonNode version = root.get("version");
        if (version == null || !version.isIntegralNumber() || (version.asInt() != 1 && version.asInt() != 2)) {
            return CheckResultRead.invalid(CheckInvalidReason.BAD_VERSION);
        }
        Optional<CheckOutcome> outcome = outcomeOf(root.get("outcome"));
        if (outcome.isEmpty()) {
            return CheckResultRead.invalid(CheckInvalidReason.BAD_OUTCOME);
        }
        return CheckResultRead.of(new CheckResultBlock(
                version.asInt(),
                outcome.get(),
                text(root.get("summary"), SUMMARY_MAX),
                findings(root.get("findings")),
                texts(root.get("questions"), QUESTIONS_MAX, QUESTION_MAX),
                texts(root.get("followUpCandidates"), FOLLOW_UPS_MAX, FOLLOW_UP_MAX),
                texts(root.get("sourceFailures"), SOURCE_FAILURES_MAX, SOURCE_FAILURE_MAX),
                version.asInt() == 2 ? report(root.get("report")) : null));
    }

    /** 앞뒤를 코드 울타리({@code ```json} 등)로 감쌌으면 벗긴다. */
    private static String stripFence(String body) {
        if (!body.startsWith(CODE_FENCE)) {
            return body;
        }
        int lineEnd = body.indexOf('\n');
        String inner = lineEnd < 0 ? "" : body.substring(lineEnd + 1).strip();
        return inner.endsWith(CODE_FENCE)
                ? inner.substring(0, inner.length() - CODE_FENCE.length()).strip()
                : inner;
    }

    /** 모델이 내는 값은 {@code FINDINGS} 와 {@code NOTHING_NEW} 뿐이다. {@code INVALID_RESULT} 는 이 코드가 정하는 값이다. */
    private static Optional<CheckOutcome> outcomeOf(JsonNode node) {
        String value = text(node, SHORT_FIELD_MAX);
        if (CheckOutcome.FINDINGS.name().equals(value)) {
            return Optional.of(CheckOutcome.FINDINGS);
        }
        if (CheckOutcome.NOTHING_NEW.name().equals(value)) {
            return Optional.of(CheckOutcome.NOTHING_NEW);
        }
        return Optional.empty();
    }

    private static List<Finding> findings(JsonNode array) {
        List<Finding> found = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return found;
        }
        for (JsonNode element : array) {
            if (found.size() >= FINDINGS_MAX) {
                break;
            }
            if (element.isObject()) {
                found.add(finding(element));
            }
        }
        return List.copyOf(found);
    }

    private static Finding finding(JsonNode node) {
        return new Finding(
                text(node.get("area"), AREA_MAX),
                text(node.get("topicKey"), TOPIC_KEY_MAX),
                text(node.get("title"), TITLE_MAX),
                text(node.get("sourceUrl"), SOURCE_URL_MAX),
                text(node.get("checkedAt"), SHORT_FIELD_MAX),
                text(node.get("publishedAt"), SHORT_FIELD_MAX),
                text(node.get("freshness"), SHORT_FIELD_MAX),
                text(node.get("whyItMatters"), WHY_IT_MATTERS_MAX),
                texts(node.get("facts"), ITEMS_MAX, ITEM_MAX),
                texts(node.get("inferences"), ITEMS_MAX, ITEM_MAX),
                texts(node.get("unknowns"), ITEMS_MAX, ITEM_MAX),
                next(node.get("next")),
                text(node.get("changeSinceLast"), CHANGE_SINCE_LAST_MAX));
    }

    private static Next next(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new Next(text(node.get("type"), SHORT_FIELD_MAX), text(node.get("text"), NEXT_TEXT_MAX));
    }

    /** 모델이 쓴 승인 번호와 근거 주소는 믿지 않고 읽지 않는다. */
    private static CheckResultBlock.ReportDraft report(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new CheckResultBlock.ReportDraft(
                texts(node.get("changed"), REPORT_CHANGED_MAX, REPORT_LINE_MAX),
                texts(node.get("done"), REPORT_DONE_MAX, REPORT_LINE_MAX),
                texts(node.get("next"), REPORT_NEXT_MAX, REPORT_LINE_MAX));
    }

    /** 문자열 칸만 읽는다. 문자열이 아니거나 비어 있으면 {@code null} 이다. 앞뒤 공백은 벗기고 상한에서 자른다. */
    private static String text(JsonNode node, int max) {
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : clip(value, max);
    }

    /** 문자열 원소만 모은다. 문자열이 아니거나 빈 원소는 건너뛰고, 상한을 넘는 원소는 버린다. */
    private static List<String> texts(JsonNode array, int maxItems, int maxLength) {
        List<String> items = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return items;
        }
        for (JsonNode element : array) {
            if (items.size() >= maxItems) {
                break;
            }
            String value = text(element, maxLength);
            if (value != null) {
                items.add(value);
            }
        }
        return List.copyOf(items);
    }

    /** UTF-16 대리쌍 한가운데에서 자르지 않는다. */
    private static String clip(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
}
