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
 * @param name 앞머리의 {@code name}
 * @param description 앞머리의 {@code description}
 */
public record SkillFrontmatter(String name, String description) {

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
        String description = textOf(values.get("description"));
        if (name.isEmpty()) {
            throw invalid("SKILL.md frontmatter needs a name");
        }
        if (description.isEmpty()) {
            throw invalid("SKILL.md frontmatter needs a description");
        }
        return new SkillFrontmatter(name, description);
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
