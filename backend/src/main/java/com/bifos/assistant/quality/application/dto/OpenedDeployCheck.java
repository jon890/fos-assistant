package com.bifos.assistant.quality.application.dto;

/**
 * 확인 줄을 열어 본 결과다. 실행기가 띄울 줄 번호를 함께 담는다.
 *
 * @param runId 확인 줄의 내부 번호. API 로 내보내지 않는다
 */
public record OpenedDeployCheck(Long runId, StartedDeployCheck started) {
}
