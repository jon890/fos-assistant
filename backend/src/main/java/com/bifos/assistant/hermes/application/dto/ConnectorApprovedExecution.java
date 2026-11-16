package com.bifos.assistant.hermes.application.dto;

/**
 * DB에서 읽은 승인 실행 원문이다. 로그의 자동 문자열 변환에도 권한 원문을 남기지 않는다.
 *
 * @param resume 사람 화면에서 멈춘 승인을 이어 갈 때 그 탭과 단계다. 처음 실행이면 null 이다
 */
public record ConnectorApprovedExecution(String ticket, String argsJson, String argsSha256, Resume resume) {

    public ConnectorApprovedExecution(String ticket, String argsJson, String argsSha256) {
        this(ticket, argsJson, argsSha256, null);
    }


    /** 플러그인이 넘기기와 함께 낸 이어 갈 단계와 남긴 탭이다. Control Plane 은 단계의 뜻을 모르고 그대로 돌려준다. */
    public record Resume(String tab, String step) {
    }


    @Override
    public String toString() {
        return "ConnectorApprovedExecution[redacted]";
    }
}
