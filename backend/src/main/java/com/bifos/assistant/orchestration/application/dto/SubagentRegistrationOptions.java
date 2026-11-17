package com.bifos.assistant.orchestration.application.dto;

/**
 * 하위 에이전트 등록 본문의 서명하지 않는 선택 칸이다. 끝 소식으로 깨울 자식인지 정하는 데 쓴다(ADR-20261117 / native-delegation-wake).
 *
 * @param parentSubagentId 부모가 하위 에이전트면 그 번호. 최상위 자식이면 {@code null}
 * @param reportsStop 플러그인이 이 자식의 끝 소식을 보내는가
 * @param childGoal 맡긴 일. 없으면 {@code null}
 */
public record SubagentRegistrationOptions(String parentSubagentId, boolean reportsStop, String childGoal) {


    /** 옛 플러그인의 등록이다. 깨우지 않는다. */
    public static final SubagentRegistrationOptions NONE = new SubagentRegistrationOptions(null, false, null);

    /** 본문 값은 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "SubagentRegistrationOptions[topLevel=" + (parentSubagentId == null || parentSubagentId.isBlank())
            + ", reportsStop=" + reportsStop
            + ", childGoalChars=" + (childGoal == null ? 0 : childGoal.length()) + "]";
    }
}
