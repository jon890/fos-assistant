package com.bifos.assistant.quality.application.dto;

/**
 * 루트 실행의 자식 실행 하나다.
 *
 * @param delegated Control Plane 이 맡긴 위임 자식인가({@code delegation_key} 가 있다)
 * @param status 실행 줄의 상태 이름
 */
public record ChildObservation(boolean delegated, String status) {
}
