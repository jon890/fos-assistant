package com.bifos.assistant.connector.connection.application.dto;

/**
 * 브라우저 중계 서명 비밀을 바꾼 뒤 브라우저를 쓰는 플러그인 바인딩을 다시 설치한 결과다(서버 비밀값 스펙 REQ18).
 *
 * @param total 다시 설치하려 한 바인딩 수
 * @param restartPending 다시 설치해 재시작 대기가 된 수. 관리자의 반영 완료 뒤 새 표식으로 돈다
 * @param failed 다시 설치하지 못한 수. 바인딩 화면에 실패로 보인다
 * @param catalogUnavailable 플러그인 목록을 읽지 못해 아무것도 다시 설치하지 못했는가
 */
public record BrowserBindingReinstall(int total, int restartPending, int failed, boolean catalogUnavailable) {
}
