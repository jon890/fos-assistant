package com.bifos.assistant.skill.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code SKILL.md} 앞머리에서 읽은 이름과 설명이다.
 *
 * <p>앞머리는 파일 맨 앞의 {@code ---} 줄과 그다음 {@code ---} 줄 사이의 YAML 이다. Hermes 가 스킬 목록에
 * 쓰는 이름과 설명이 여기서 나오므로, 올릴 때 같은 자리를 같은 규칙으로 읽어 둘이 어긋나지 않게 한다.
 *
 * <p>본문이 없어도 읽기는 성공한다. 본문 검사는 저장할 때 {@link SkillService} 가 한다. 설명만 읽는 자리가
 * 본문 없는 옛 스킬도 읽을 수 있어야 하기 때문이다.
 *
 * @param name 앞머리의 {@code name}. 앞뒤 공백을 뺀 값이다
 * @param description 앞머리의 {@code description}. 앞뒤 공백을 뺀 값이고 화면에 보이는 값이다
 * @param rawDescription 앞머리의 {@code description} 을 문자열로 바꾼 원래 값. 앞뒤를 빼지 않는다
 * @param hasBody 닫는 {@code ---} 줄 뒤에 공백이 아닌 글이 있는가
 */
public record SkillFrontmatter(String name, String description, String rawDescription, boolean hasBody) {

    private static final String FENCE = "---";

    /**
     * 앞머리를 읽는다. 없거나 YAML 이 아니거나 {@code name} 이나 {@code description} 이 비어 있으면
     * {@link ErrorCode#VALIDATION_FAILED} 다.
     */
    public static SkillFrontmatter parse(String skillMd) {
        if (skillMd == null) {
            throw invalid("SKILL.md is required");
        }
        String[] lines = skillMd.split("\r?\n", -1);
        if (lines.length < 2 || !FENCE.equals(lines[0].strip())) {
            throw invalid("SKILL.md must start with a --- frontmatter block");
        }
        int end = -1;
        for (int index = 1; index < lines.length; index++) {
            if (FENCE.equals(lines[index].strip())) {
                end = index;
                break;
            }
        }
        if (end < 0) {
            throw invalid("SKILL.md frontmatter is not closed with ---");
        }
        String yaml = String.join("\n", Arrays.copyOfRange(lines, 1, end));
        Object loaded;
        try {
            // 태그로 임의의 객체를 만들지 않는 생성기다. 사용자가 올린 글을 그대로 읽는 자리라서다.
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "SKILL.md frontmatter is not valid YAML", ex);
        }
        if (!(loaded instanceof Map<?, ?> values)) {
            throw invalid("SKILL.md frontmatter must be a YAML mapping");
        }
        String name = textOf(values.get("name"));
        Object descriptionValue = values.get("description");
        String description = textOf(descriptionValue);
        if (name.isEmpty()) {
            throw invalid("SKILL.md frontmatter needs a name");
        }
        if (description.isEmpty()) {
            throw invalid("SKILL.md frontmatter needs a description");
        }
        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length));
        return new SkillFrontmatter(name, description, String.valueOf(descriptionValue), !body.isBlank());
    }

    /**
     * Hermes 가 새 스킬을 만들 때 60자 한도에 견주는 설명 글자 수다.
     *
     * <p>Hermes v0.21.5 {@code tools/skill_manager_tool.py} 의 {@code len(desc.strip().strip("'\""))} 와 같다.
     * 앞뒤 공백을 뺀 뒤 양 끝의 {@code '} 와 {@code "} 를 몇 개든 빼고, Python 처럼 code point 로 센다.
     * {@code String.length()} 로 세면 이모지 하나가 둘로 세어져 Hermes 보다 엄격해진다.
     */
    public int indexedDescriptionLength() {
        String text = rawDescription.strip();
        int start = 0;
        int end = text.length();
        while (start < end && isQuote(text.charAt(start))) {
            start++;
        }
        while (end > start && isQuote(text.charAt(end - 1))) {
            end--;
        }
        return text.codePointCount(start, end);
    }

    /**
     * Hermes 가 저장할 때마다 1024자 한도에 견주는 설명 글자 수다.
     *
     * <p>Hermes v0.21.5 {@code tools/skill_manager_tool.py} 의 {@code len(str(parsed["description"]))} 와 같다.
     * 앞뒤를 빼지 않고 code point 로 센다.
     */
    public int rawDescriptionLength() {
        return rawDescription.codePointCount(0, rawDescription.length());
    }

    private static boolean isQuote(char value) {
        return value == '\'' || value == '"';
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
