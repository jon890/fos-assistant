package com.bifos.assistant.agent.application.port;

import java.util.List;

/** profile 에 올라간 스킬 파일을 묻고 지운다. 구현은 스킬 쪽이 갖는다. */
public interface ProfileSkillFiles {

    /** 그 profile 에 올린 스킬이 하나라도 있는지 본다. */
    boolean hasUploaded(String profile);


    /**
     * 그 profile 에 올린 스킬 가운데 앞머리가 환경 값이나 자격 증명 파일을 요청하는 것의 이름이다. 없으면 빈 목록이다.
     * 셸 도구가 켜지면 Hermes 가 그 값을 실행 공간에 넣으므로 도구 저장이 이것으로 거절한다(ADR-086).
     */
    List<String> uploadedRequestingSecrets(String profile);


    /**
     * 그 profile 에 올린 스킬의 이름이다. 이름순이다. 지금 버전과, 게시가 timeout 으로 끝나 표식 없이 남은 더 새 버전을 함께
     * 본다. 스킬 본문은 읽지 않는다. 디렉터리를 읽지 못하면 빈 목록이다.
     */
    List<String> uploadedNames(String profile);


    /** 그 profile 에 올린 스킬을 모두 지운다. */
    void deleteAll(String profile);
}
