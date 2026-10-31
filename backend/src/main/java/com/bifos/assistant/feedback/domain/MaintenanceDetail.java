package com.bifos.assistant.feedback.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 정비 사건의 근거({@code decision_feedback_event.detail})다. 제목과 본문은 담지 않는다.
 *
 * <p>후보 사건은 규칙 버전과 모드와 그때의 신호 값({@code evidence})을 담고, 확인 사건은 확인한 후보 사건의 번호({@code
 * candidateId})를 담는다. 신호 값의 이름은 규칙이 정하므로 규칙이 바뀌어도 스키마는 그대로다(ADR-20261015 / memory-maintenance).
 *
 * @param ruleVersion 후보를 만든 규칙의 버전. 확인 사건은 null
 * @param mode 후보를 만든 모드. 제안 모드는 {@code PROPOSE}. 확인 사건은 null
 * @param evidence 그때의 신호 값. 확인 사건은 null
 * @param candidateId 확인한 후보 사건의 번호. 후보 사건은 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaintenanceDetail(Integer ruleVersion, String mode, Map<String, Object> evidence, Long candidateId) {


    /** 제안 모드의 값이다. 동작을 적용하지 않고 후보와 근거만 남긴다. */
    public static final String MODE_PROPOSE = "PROPOSE";

    public static MaintenanceDetail proposed(int ruleVersion, String mode, Map<String, Object> evidence) {
        return new MaintenanceDetail(ruleVersion, mode, evidence, null);
    }


    public static MaintenanceDetail reviewOf(Long candidateId) {
        return new MaintenanceDetail(null, null, null, candidateId);
    }
}
