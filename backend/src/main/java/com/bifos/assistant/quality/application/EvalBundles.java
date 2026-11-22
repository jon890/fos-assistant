package com.bifos.assistant.quality.application;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.EvalBundle;
import com.bifos.assistant.quality.domain.EvalBundle.EvalExpect;
import com.bifos.assistant.quality.domain.EvalBundle.EvalQuestion;
import com.bifos.assistant.quality.domain.EvalBundle.EvalTurn;
import com.bifos.assistant.shared.config.LiveProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 평가 묶음 파일을 기동 때 모두 읽고 검사한다(docs/features/agent-quality/design.md 의 「묶음 파일」).
 *
 * <p>틀린 파일이 하나라도 있으면 기동을 멈춘다. 운영에서 틀린 묶음이 조용히 빠지면 기능 확인이 돌지 않은 것을 모른다.
 */
@Component
public class EvalBundles {

    /** 문항이 쓸 수 있는 자리다. 운영에 따라 달라지는 값이라 실행기가 평가 사용자의 데이터로 채운다. */
    public static final Set<String> SLOTS = Set.of("otherAgentName", "otherAgentCode", "connectorTitle", "connectorId");

    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z]+)\\}");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private final Map<String, EvalBundle> bundles;


    @Autowired
    public EvalBundles(LiveProperties<QualityProperties> properties, ResourceLoader loader) {
        this(new PathMatchingResourcePatternResolver(loader), properties.current().bundleLocation());
    }


    EvalBundles(ResourcePatternResolver resolver, String location) {
        this.bundles = load(resolver, location);
    }


    /** 묶음 id 순이다. */
    public List<EvalBundle> all() {
        return List.copyOf(bundles.values());
    }


    public Optional<EvalBundle> find(String id) {
        return Optional.ofNullable(bundles.get(id));
    }


    /**
     * 문항의 자리를 채운다. 쓴 자리의 값이 하나라도 비면 비어 있다. 그 문항은 건너뛴다.
     *
     * @param values 자리 이름과 값. 값이 없는 자리는 빼거나 빈 글로 준다
     */
    public static Optional<EvalQuestion> fill(EvalQuestion question, Map<String, String> values) {
        boolean[] missing = {false};
        UnaryOperator<String> text = value -> {
            if (value == null) {
                return null;
            }
            Matcher matcher = SLOT.matcher(value);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String filled = values.get(matcher.group(1));
                if (filled == null || filled.isBlank()) {
                    missing[0] = true;
                    filled = matcher.group();
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(filled));
            }
            matcher.appendTail(out);
            return out.toString();
        };
        UnaryOperator<List<String>> list = items -> items.stream().map(text).toList();
        List<EvalTurn> turns = question.turns().stream()
            .map(turn -> new EvalTurn(text.apply(turn.text()), turn.attachment(), turn.card()))
            .toList();
        EvalExpect expect = question.expect();
        EvalExpect filled = new EvalExpect(list.apply(expect.links()), list.apply(expect.linkPrefixes()), list.apply(expect.noLinkPrefixes()),
            list.apply(expect.tools()), list.apply(expect.includes()), list.apply(expect.absent()), list.apply(expect.instructionIncludes()),
            expect.cardStatus());
        if (missing[0]) {
            return Optional.empty();
        }
        return Optional.of(new EvalQuestion(question.id(), question.tag(), turns, filled, question.keepConversation()));
    }


    private static Map<String, EvalBundle> load(ResourcePatternResolver resolver, String location) {
        String folder = location.endsWith("/") ? location : location + "/";
        Resource[] files;
        try {
            files = resolver.getResources(folder + "*.json");
        } catch (IOException ex) {
            throw new IllegalStateException("cannot list eval bundles at " + folder, ex);
        }
        List<EvalBundle> loaded = new ArrayList<>();
        for (Resource file : files) {
            loaded.add(read(file));
        }
        if (loaded.isEmpty()) {
            // 운영 jar 에서 폴더를 찾지 못해도 조용히 빈 목록으로 뜨면 평가가 돌지 않은 것을 모른다.
            throw new IllegalStateException("no eval bundles at " + folder);
        }
        loaded.sort(Comparator.comparing(EvalBundle::id));
        Map<String, EvalBundle> byId = new LinkedHashMap<>();
        for (EvalBundle bundle : loaded) {
            if (byId.put(bundle.id(), bundle) != null) {
                throw invalid(bundle.id() + ".json", "duplicate bundle id " + bundle.id());
            }
        }
        return byId;
    }


    private static EvalBundle read(Resource file) {
        String name = file.getFilename() == null ? "?" : file.getFilename();
        EvalBundle bundle;
        try (InputStream in = file.getInputStream()) {
            bundle = JSON.readValue(in, EvalBundle.class);
        } catch (IOException | JacksonException ex) {
            throw new IllegalStateException("cannot read eval bundle " + name + ": " + ex.getMessage(), ex);
        }
        validate(name, bundle);
        return bundle;
    }


    private static void validate(String name, EvalBundle bundle) {
        String expectedId = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
        if (bundle.id() == null || !bundle.id().equals(expectedId)) {
            throw invalid(name, "id must equal the file name " + expectedId);
        }
        if (!ID.matcher(bundle.id()).matches()) {
            throw invalid(name, "id must be lowercase letters, digits and hyphens");
        }
        if (bundle.title() == null || bundle.title().isBlank()) {
            throw invalid(name, "title is empty");
        }
        if (bundle.timeoutMinutes() <= 0) {
            throw invalid(name, "timeoutMinutes must be positive");
        }
        if (!EvalBundle.PassRule.RATIO.equals(bundle.pass().type()) && !bundle.pass().external()) {
            // 답변 품질 기준(answer-quality)은 그 판정을 옮기는 PR 이 더한다.
            throw invalid(name, "unsupported pass type " + bundle.pass().type());
        }
        if (bundle.pass().ratio() <= 0 || bundle.pass().ratio() > 1) {
            throw invalid(name, "pass ratio must be in (0, 1]");
        }
        if (bundle.questions().isEmpty()) {
            throw invalid(name, "questions are empty");
        }
        Set<String> ids = new HashSet<>();
        for (EvalQuestion question : bundle.questions()) {
            if (question.id() == null || !ID.matcher(question.id()).matches()) {
                throw invalid(name, "question id must be lowercase letters, digits and hyphens: " + question.id());
            }
            if (!ids.add(question.id())) {
                throw invalid(name, "duplicate question id " + question.id());
            }
            if (question.tag() == null || question.tag().isBlank()) {
                throw invalid(name, "question " + question.id() + " has no tag");
            }
            if (question.turns().isEmpty()) {
                throw invalid(name, "question " + question.id() + " has no turns");
            }
            for (EvalTurn turn : question.turns()) {
                if (turn.text() == null || turn.text().isBlank()) {
                    throw invalid(name, "question " + question.id() + " has an empty turn");
                }
                slots(name, question.id(), turn.text());
                if (turn.attachment() != null && !new ClassPathResource("quality/" + turn.attachment()).exists()) {
                    throw invalid(name, "question " + question.id() + " attaches a missing resource " + turn.attachment());
                }
            }
            if (bundle.pass().external() && !question.keepConversation()) {
                // 밖의 채점기가 대화를 읽어야 하므로 통과해도 지우지 않는다.
                throw invalid(name, "question " + question.id() + " of an external bundle must keep its conversation");
            }
            EvalExpect expect = question.expect();
            List<List<String>> lists = List.of(expect.links(), expect.linkPrefixes(), expect.noLinkPrefixes(), expect.tools(),
                expect.includes(), expect.absent(), expect.instructionIncludes());
            if (!bundle.pass().external() && lists.stream().allMatch(List::isEmpty) && expect.cardStatus() == null) {
                // 기대가 없으면 무엇을 답해도 통과한다. 옛 평가 세트의 형식 검사와 같이 막는다.
                throw invalid(name, "question " + question.id() + " has no expectation");
            }
            for (List<String> values : lists) {
                for (String value : values) {
                    if (value == null || value.isBlank()) {
                        throw invalid(name, "question " + question.id() + " has an empty expectation value");
                    }
                    slots(name, question.id(), value);
                }
            }
            if (expect.cardStatus() != null && Arrays.stream(ActionStatus.values()).noneMatch(status -> status.name().equals(expect.cardStatus()))) {
                throw invalid(name, "question " + question.id() + " expects an unknown card status " + expect.cardStatus());
            }
        }
    }


    private static void slots(String name, String questionId, String text) {
        Matcher matcher = SLOT.matcher(text);
        while (matcher.find()) {
            if (!SLOTS.contains(matcher.group(1))) {
                throw invalid(name, "question " + questionId + " uses unknown slot {" + matcher.group(1) + "}");
            }
        }
    }


    private static IllegalStateException invalid(String name, String reason) {
        return new IllegalStateException("invalid eval bundle " + name + ": " + reason);
    }
}
