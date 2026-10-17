package com.bifos.assistant.skill.library.application;

import com.bifos.assistant.skill.library.domain.SkillBundle;
import com.bifos.assistant.skill.library.infra.PreviousSkillStore;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 이전 버전의 보관과 시각 조회가 실패해도 이미 끝난 게시와 원문 읽기를 유지한다. */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "com.bifos.assistant.skill.library.application.SkillService")
class PreviousSkillVersions {

    private final PreviousSkillStore store;


    /** 게시가 끝난 뒤 바뀌기 전 스킬을 남긴다. 실패하면 기존 경고만 남긴다. */
    void write(String profile, SkillBundle replaced) {
        try {
            store.writePrevious(profile, replaced);
        } catch (RuntimeException ex) {
            log.warn("바뀌기 전 스킬을 이전 버전으로 남기지 못했다 profile={} skill={}", profile, replaced.name(), ex);
        }
    }


    /** 게시가 끝난 뒤 이전 버전을 지운다. 실패해도 저장과 지우기를 실패로 바꾸지 않는다. */
    void delete(String profile, String name) {
        try {
            store.deletePrevious(profile, name);
        } catch (RuntimeException ex) {
            log.warn("스킬의 이전 버전을 지우지 못했다 profile={} skill={}", profile, name, ex);
        }
    }


    /** 남긴 시각 파일만 읽는다. 없거나 읽지 못하면 {@code null} 이고 읽기와 저장을 막지 않는다. */
    Instant savedAt(String profile, String name) {
        try {
            return store.previousSavedAt(profile, name).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("스킬의 이전 버전을 읽지 못했다 profile={} skill={}", profile, name, ex);
            return null;
        }
    }
}
