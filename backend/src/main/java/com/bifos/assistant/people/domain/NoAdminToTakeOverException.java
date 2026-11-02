package com.bifos.assistant.people.domain;

/**
 * 정리할 사용자의 그룹 공개 에이전트를 넘겨받을 켜진 관리자가 그룹에 없다(ADR-20261101 / user-purge).
 *
 * <p>정리는 아무것도 지우지 않고 멈춘다. 관리자를 새로 켜거나 예약을 취소하면 풀린다. 화면에 보이는 오류가 아니라 정리 작업의 로그로만
 * 알리므로 {@code ApiException} 이 아니다.
 */
public class NoAdminToTakeOverException extends RuntimeException {

    public NoAdminToTakeOverException() {
        super("no enabled admin can take over the group agents");
    }
}
