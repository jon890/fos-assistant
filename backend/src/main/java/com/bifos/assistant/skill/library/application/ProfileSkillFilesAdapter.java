package com.bifos.assistant.skill.library.application;

import com.bifos.assistant.skill.library.application.dto.SkillFrontmatter;
import com.bifos.assistant.agent.application.port.ProfileSkillFiles;
import com.bifos.assistant.skill.library.domain.SkillBundle;
import com.bifos.assistant.skill.library.infra.SkillStore;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** {@link ProfileSkillFiles} 를 {@link SkillStore} 로 넘겨 준다. */
@Slf4j
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


    /** 읽지 못하면 빈 목록과 경고 로그다. 에이전트 목록이 스킬 디렉터리 장애로 실패하지 않게 한다. */
    @Override
    public List<String> uploadedNames(String profile) {
        try {
            return skillStore.uploadedNames(profile);
        } catch (RuntimeException ex) {
            log.warn("uploaded skill names unreadable profile={} reason={}", profile, ex.getClass().getSimpleName());
            return List.of();
        }
    }


    @Override
    public void deleteAll(String profile) {
        skillStore.deleteAll(profile);
    }
}
