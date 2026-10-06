package com.bifos.assistant.skill.application;

import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.infra.SkillStore;
import java.util.List;
import java.util.stream.Stream;
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

    /** 지금 버전과, 게시가 timeout 으로 끝나 표식 없이 남은 더 새 버전을 함께 본다. Hermes 가 그 버전을 반영했을 수 있다. */
    @Override
    public List<String> uploadedRequestingSecrets(String profile) {
        return Stream.concat(
                        skillStore.readCurrent(profile).values().stream(), skillStore.readAllPending(profile).stream())
                .filter(bundle -> SkillFrontmatter.storedRequestsSecrets(bundle.skillMd()))
                .map(SkillBundle::name)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public void deleteAll(String profile) {
        skillStore.deleteAll(profile);
    }
}
