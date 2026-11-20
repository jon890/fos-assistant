package com.bifos.assistant.connector.action.application.dto;

/**
 * 승인한 Control Plane 동작의 결과다.
 *
 * @param message 대화에 남길 알림 줄의 글
 */
public record ControlPlaneActionOutcome(boolean succeeded, String message) {

    public static ControlPlaneActionOutcome done(String message) {
        return new ControlPlaneActionOutcome(true, message);
    }


    public static ControlPlaneActionOutcome failed(String message) {
        return new ControlPlaneActionOutcome(false, message);
    }
}
