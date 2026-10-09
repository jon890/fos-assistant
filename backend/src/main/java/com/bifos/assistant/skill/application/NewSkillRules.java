package com.bifos.assistant.skill.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.infra.SkillProperties;
import com.bifos.assistant.skill.infra.SkillPublisher;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 새 스킬일 때만 보는 검사다. Hermes 기본 스킬 이름, 올린 스킬 수 한도, 새 스킬 설명 60자를 본다. */
@Component
@RequiredArgsConstructor
public class NewSkillRules {

    private final SkillPublisher publisher;
    private final SkillProperties properties;

    /** 새 스킬일 때만 하는 검사다. Hermes 기본 스킬 이름, 올린 스킬 수 한도, 새 스킬 설명 60자를 본다. */
    public void requireCreatable(
            String profile,
            String name,
            SkillFrontmatter frontmatter,
            Map<String, SkillBundle> current,
            Map<String, SkillBundle> pending) {
        if (publisher.list(profile).stream().anyMatch(s -> s.name().equals(name))) {
            throw new ApiException(ErrorCode.SKILL_NAME_TAKEN, "Hermes already has a skill with this name");
        }
        int max = properties.maxPerAgent();
        if (uploadedNames(current, pending).size() >= max) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "an agent can have at most " + max + " uploaded skills");
        }
        if (frontmatter.indexedDescriptionLength() > SkillService.MAX_NEW_DESCRIPTION_CHARS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "a new skill description can be at most " + SkillService.MAX_NEW_DESCRIPTION_CHARS + " characters");
        }
    }

    public static Set<String> uploadedNames(Map<String, SkillBundle> current, Map<String, SkillBundle> pending) {
        Set<String> names = new HashSet<>(current.keySet());
        names.addAll(pending.keySet());
        return names;
    }
}
