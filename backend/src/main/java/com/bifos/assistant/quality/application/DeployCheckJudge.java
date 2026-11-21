package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.CheckObservation;
import com.bifos.assistant.quality.application.dto.ChildObservation;
import com.bifos.assistant.quality.application.dto.FeatureVerdict;
import com.bifos.assistant.quality.application.dto.TurnObservation;
import com.bifos.assistant.quality.domain.type.CheckReason;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 배포 뒤 확인의 기능별 규칙 판정이다(docs/features/agent-quality/design.md 의 「기능별 문항과 판정」). 네트워크와 DB 를 쓰지 않는다.
 *
 * <p>판정 모델을 쓰지 않는다. 기능이 동작했는지를 실행 사건, 결과물, 만들어진 데이터로만 본다.
 */
@Component
public class DeployCheckJudge {

    static final String WEB_SEARCH = "web_search";

    static final String FILE_SPACE_PUT = "file_space_put";

    static final String ATTACHMENT_INSPECT = "attachment_inspect";

    static final String IMAGE_KIND = "IMAGE";

    /** 실행 줄과 발화 줄이 성공으로 끝난 상태 이름이다. */
    static final String SUCCEEDED = "SUCCEEDED";

    private static final Set<String> HERMES_TIMEOUTS = Set.of("HERMES_RUN_TIMEOUT");

    private static final Set<String> BUSY_CODES = Set.of("USER_BUSY", "CONVERSATION_BUSY");

    /**
     * 「출처」 가 든 마크다운 제목 줄이다. {@code test/model-eval/answer/score.ts} 와 같은 뜻이다. JavaScript 의 {@code \s} 처럼 줄바꿈 없는 공백과
     * 전각 공백도 공백으로 받도록 이 클래스의 정규식은 유니코드 문자 분류를 쓴다.
     */
    private static final Pattern HASH_HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s.*출처", Pattern.UNICODE_CHARACTER_CLASS);

    /** 제목 표시 없이 「출처」 로 시작하는 줄이다. 콜론 뒤 같은 줄의 글은 절의 본문이다. */
    private static final Pattern PLAIN_HEADING = Pattern.compile(
        "^(?:참고\\s*)?출처(?:\\s*목록)?\\s*(?:\\*\\*|__)?\\s*(?:[:：]\\s*(?:\\*\\*|__)?(.*)|(?:\\([^)]*\\))?\\s*)$",
        Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern LEADING_MARKS = Pattern.compile("^[\\s>*_\\-•·]+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern NEXT_HEADING = Pattern.compile("^\\s{0,3}(?:#{1,6}\\s+\\S|(?:\\*\\*|__)[^*_]+(?:\\*\\*|__)\\s*:?\\s*$)",
        Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>()\\[\\]\"'`]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** 주소 안의 괄호 한 겹까지 받는다. */
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
        "\\[[^\\]]*\\]\\((https?://(?:[^\\s()]|\\([^\\s()]*\\))+)\\)", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?]+$");


    /** 기능 하나를 판정한다. 실패·시간 초과·승인 카드는 어느 turn 하나라도 해당하면 기능 규칙보다 먼저 본다. */
    public FeatureVerdict judge(QualityFeature feature, CheckObservation observation) {
        FeatureVerdict common = common(observation);
        if (common != null) {
            return common;
        }
        return switch (feature) {
            case SEARCH -> search(lastTurn(observation));
            case DELEGATION -> delegation(observation);
            case FILE_PUT -> filePut(observation);
            case IMAGE -> image(lastTurn(observation));
            case MEMORY_READ -> containsOrFail(lastTurn(observation), DeployCheckItems.MEMORY_FACT, CheckReason.FACT_MISSING);
            case TASK_FIRE -> taskFire(observation);
            case PHOTO_INSPECT -> photoInspect(lastTurn(observation));
        };
    }


    private static FeatureVerdict common(CheckObservation observation) {
        if (observation.timedOut()) {
            return FeatureVerdict.timedOut(CheckReason.TIMED_OUT);
        }
        for (TurnObservation turn : observation.turns()) {
            if (turn.errorCode() != null && HERMES_TIMEOUTS.contains(turn.errorCode())) {
                return FeatureVerdict.timedOut(CheckReason.HERMES_TIMEOUT);
            }
            if (turn.errorCode() != null && BUSY_CODES.contains(turn.errorCode())) {
                return FeatureVerdict.failed(CheckReason.BUSY);
            }
            if (turn.errorCode() != null || (turn.executionStatus() != null && !SUCCEEDED.equals(turn.executionStatus()))) {
                return FeatureVerdict.failed(CheckReason.RUN_FAILED);
            }
        }
        if (observation.turns().stream().anyMatch(TurnObservation::approvalRequested)) {
            return FeatureVerdict.failed(CheckReason.APPROVAL_REQUESTED);
        }
        return null;
    }


    private static FeatureVerdict search(TurnObservation turn) {
        if (!completed(turn, WEB_SEARCH)) {
            return FeatureVerdict.failed(CheckReason.NO_SEARCH);
        }
        return sourceLinks(turn.answer()).isEmpty() ? FeatureVerdict.failed(CheckReason.NO_SOURCES) : FeatureVerdict.passed();
    }


    /** 위임은 사용자 turn 의 트리에 성공한 위임 자식이 있고, 그 결과가 대화에 전해져야 통과다. */
    private static FeatureVerdict delegation(CheckObservation observation) {
        if (observation.turns().isEmpty()) {
            return FeatureVerdict.failed(CheckReason.NO_CHILD);
        }
        List<ChildObservation> delegated = observation.turns().getFirst().children().stream()
            .filter(ChildObservation::delegated)
            .toList();
        if (delegated.isEmpty()) {
            return FeatureVerdict.failed(CheckReason.NO_CHILD);
        }
        if (delegated.stream().noneMatch(child -> SUCCEEDED.equals(child.status()))) {
            return FeatureVerdict.failed(CheckReason.CHILD_FAILED);
        }
        return observation.delivered() ? FeatureVerdict.passed() : FeatureVerdict.failed(CheckReason.NOT_DELIVERED);
    }


    private static FeatureVerdict filePut(CheckObservation observation) {
        boolean called = observation.turns().stream().anyMatch(turn -> completed(turn, FILE_SPACE_PUT));
        return called && observation.vaultRows() > 0 ? FeatureVerdict.passed() : FeatureVerdict.failed(CheckReason.NO_VAULT_ROW);
    }


    private static FeatureVerdict image(TurnObservation turn) {
        return turn.artifactKinds().contains(IMAGE_KIND) ? FeatureVerdict.passed() : FeatureVerdict.failed(CheckReason.NO_IMAGE);
    }


    private static FeatureVerdict taskFire(CheckObservation observation) {
        boolean fired = SUCCEEDED.equals(observation.taskRunStatus()) && observation.taskRunExecutionId() != null;
        return fired ? FeatureVerdict.passed() : FeatureVerdict.failed(CheckReason.TASK_NOT_SUCCEEDED);
    }


    /** 사진을 묻는 turn(마지막 turn)이 원본 조회 도구를 끝냈고 답에 심은 글자가 있어야 통과다. */
    private static FeatureVerdict photoInspect(TurnObservation turn) {
        if (!completed(turn, ATTACHMENT_INSPECT)) {
            return FeatureVerdict.failed(CheckReason.NO_INSPECT);
        }
        return containsOrFail(turn, DeployCheckItems.PHOTO_MARKER, CheckReason.MARKER_MISSING);
    }


    private static FeatureVerdict containsOrFail(TurnObservation turn, String word, CheckReason reason) {
        return turn.answer().contains(word) ? FeatureVerdict.passed() : FeatureVerdict.failed(reason);
    }


    /** 그 도구의 끝 사건이 실패 없이 하나라도 있는가. */
    private static boolean completed(TurnObservation turn, String toolName) {
        return turn.tools().stream().anyMatch(tool -> !tool.failed() && toolName.equals(withoutMcpPrefix(tool.toolName())));
    }


    /** Hermes 가 붙인 서버 이름을 빼고 도구 이름을 비교한다. */
    private static String withoutMcpPrefix(String name) {
        String prefix = "mcp__";
        if (name == null || !name.startsWith(prefix)) {
            return name;
        }
        int separator = name.indexOf("__", prefix.length());
        return separator > prefix.length() ? name.substring(separator + 2) : name;
    }


    private static TurnObservation lastTurn(CheckObservation observation) {
        return observation.turns().isEmpty()
            ? new TurnObservation("", SUCCEEDED, null, List.of(), List.of(), List.of(), false)
            : observation.turns().getLast();
    }


    /**
     * 답 끝 「출처」 절의 주소다. 절 밖의 링크는 세지 않고 같은 주소는 한 번만 센다. {@code test/model-eval/answer/score.ts} 의
     * {@code sourceLinks} 와 같은 규칙이다.
     */
    static List<String> sourceLinks(String answer) {
        String[] lines = answer.split("\\r?\\n", -1);
        int start = -1;
        String rest = "";
        for (int index = 0; index < lines.length; index++) {
            String found = headingRest(lines[index]);
            if (found != null) {
                start = index;
                rest = found;
            }
        }
        if (start < 0) {
            return List.of();
        }
        List<String> body = new ArrayList<>(List.of(rest));
        for (int index = start + 1; index < lines.length; index++) {
            if (NEXT_HEADING.matcher(lines[index]).find()) {
                break;
            }
            body.add(lines[index]);
        }
        String text = String.join("\n", body);
        List<String> found = new ArrayList<>();
        Matcher links = MARKDOWN_LINK.matcher(text);
        while (links.find()) {
            found.add(links.group(1));
        }
        Matcher urls = URL.matcher(MARKDOWN_LINK.matcher(text).replaceAll(" "));
        while (urls.find()) {
            found.add(urls.group());
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String url : found) {
            unique.add(TRAILING_PUNCTUATION.matcher(url).replaceAll(""));
        }
        return List.copyOf(unique);
    }


    private static String headingRest(String line) {
        if (HASH_HEADING.matcher(line).find()) {
            return "";
        }
        Matcher match = PLAIN_HEADING.matcher(LEADING_MARKS.matcher(line).replaceFirst(""));
        if (!match.matches()) {
            return null;
        }
        return match.group(1) == null ? "" : match.group(1);
    }
}
