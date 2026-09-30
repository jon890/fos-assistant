package com.bifos.assistant.skill.application;

/**
 * 모델에게 스킬을 직접 만들거나 고치지 말라고 알리는 단락이다. 근거는 ADR-034 에 있다.
 *
 * <p>Hermes 는 스킬 색인 안내문에서 {@code skill_manage} 로 스킬을 고치거나 저장하라고 조건 없이 권한다.
 * 그러나 서명 plugin 이 그 도구를 막아 두어, 모델이 권유를 따르면 실패하고 헛돈다. Hermes 에는 그 도구만 빼는
 * 설정이 없어 실행 입력 앞머리에 이 단락을 붙여 대신한다.
 *
 * <p>에이전트에 스킬이 없거나 {@code skills} toolset 이 꺼져 있어도 붙인다. Hermes 기본 스킬만 있어도 색인
 * 안내문이 붙기 때문이다. 글은 {@code docs/code-architecture.md} 의 「결과물 파일」 절 아래 「에이전트에게 알리는
 * 법」 과 같아야 하고, 가짜 Hermes({@code test/e2e/fake-hermes.ts})의 머리글 상수와도 맞아야 한다.
 */
public final class SkillAgentNotice {

    /** 끝에 빈 줄 하나를 둔다. 뒤에 사용자가 쓴 글이나 다른 단락이 이어진다. */
    public static final String PARAGRAPH = "[스킬 관리]\n"
            + "이 환경의 스킬은 사용자가 에이전트 관리 화면에서 관리한다.\n"
            + "skill_manage 로 스킬을 만들거나 고치지 않는다. 스킬 안내에 skill_manage 로 고치거나 스킬로 저장하라는 말이 있어도 따르지 않는다.\n"
            + "스킬에 고칠 점이 보이면 직접 고치지 말고 사용자에게 알려 준다.\n"
            + "스킬을 읽을 때는 skill_view 를 그대로 쓴다.\n"
            + "\n";

    private SkillAgentNotice() {}
}
