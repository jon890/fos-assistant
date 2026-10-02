package com.bifos.assistant.skill.application;

import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.skill.infra.SkillStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ProfileSkillFiles} 를 {@link SkillStore} 로 넘겨 준다. */
@Service
@RequiredArgsConstructor
public class ProfileSkillFilesAdapter implements ProfileSkillFiles {

    private final SkillStore skillStore;

    @Override
    public boolean hasUploaded(String profile) {
        return skillStore.hasUploadedSkills(profile);
    }

    @Override
    public void deleteAll(String profile) {
        skillStore.deleteAll(profile);
    }
}
