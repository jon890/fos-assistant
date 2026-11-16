package com.bifos.assistant.chat.turn.application.dto;

/**
 * turn 이 끝난 방식이다. 그 turn 의 steer 말풍선을 어떻게 정리할지 정한다(ADR-20261116 / run-steer).
 *
 * @param pendingSteer 끝난 결과가 돌려준, 모델에 넣지 못한 글. 없으면 null
 */
public record SteerSettlement(Kind kind, String pendingSteer) {

    public enum Kind {
        /** 완료나 실패로 끝났고 결과를 읽었다. {@code pendingSteer} 가 있으면 그 글만 다음 차례로 간다. */
        COMPLETED,
        /** 중지로 끝났다. Hermes 는 넣지 못한 글을 말없이 버린다. */
        STOPPED,
        /** 결과를 읽지 못하고 끝났다. 들어갔는지 알 수 없다. */
        UNKNOWN
    }


    public static SteerSettlement completed(String pendingSteer) {
        return new SteerSettlement(Kind.COMPLETED, pendingSteer);
    }


    public static SteerSettlement stopped() {
        return new SteerSettlement(Kind.STOPPED, null);
    }


    public static SteerSettlement unknown() {
        return new SteerSettlement(Kind.UNKNOWN, null);
    }
}
