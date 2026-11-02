package com.bifos.assistant.chat.delivery.domain;

/**
 * 묶음을 여는 사이 다른 쪽이 그 결과를 먼저 전했다(ADR-075).
 *
 * <p>예상된 경합이다. 던진 트랜잭션은 알림 줄과 묶음, 전달 표시를 함께 되돌리고, 다음 깨우기가 그 결과를 빼고 다시 모은다.
 * 자동 turn 을 돌리는 쪽은 이 예외를 경고로만 남기고 대화에 오류 사건을 내지 않는다.
 */
public class ResultAlreadyDeliveredException extends RuntimeException {

    private static final long serialVersionUID = 1L;


    public ResultAlreadyDeliveredException(String source, int changed, int expected) {
        super("result already delivered by another turn source=" + source + " changed=" + changed + " expected=" + expected);
    }
}
