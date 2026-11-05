package com.bifos.assistant.proactive.check.domain;

import java.util.List;

/**
 * 먼저 살펴보기가 남긴 다섯 칸 보고다. 모델이 아닌 Control Plane 이 근거와 승인 항목을 채운다.
 *
 * <p>뒤의 두 칸은 화면에 보이지 않는 되풀이 판정 재료다. 보고를 남긴 살펴보기만 사용자에게 알린 것이므로, 다음 살펴보기는 그 점검 대화의
 * 최근 보고에서 이미 알린 발견과 받아들인 문제 키를 읽는다. 두 칸이 없는 옛 보고는 빈 목록으로 읽는다.
 *
 * @param announced 「새로 알릴 것」 으로 알린 발견 가운데 주제 키가 있는 것
 * @param acceptedProblemKeys 받아들인 문제 후보의 정규화한 문제 키
 */
public record CheckReport(
    List<String> changed,
    List<String> done,
    List<String> evidence,
    List<String> needsApproval,
    List<String> next,
    List<ReportedFinding> announced,
    List<String> acceptedProblemKeys) {

    public CheckReport {
        announced = announced == null ? List.of() : List.copyOf(announced);
        acceptedProblemKeys = acceptedProblemKeys == null ? List.of() : List.copyOf(acceptedProblemKeys);
    }


    /** 판정 재료 없이 다섯 칸만 담는다. */
    public CheckReport(
        List<String> changed, List<String> done, List<String> evidence, List<String> needsApproval, List<String> next) {
        this(changed, done, evidence, needsApproval, next, List.of(), List.of());
    }
}
