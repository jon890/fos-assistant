package com.bifos.assistant.chat.conversation.domain.type;

/** 도는 실행에 끼워 넣은 사용자 글(steer 말풍선)의 상태다(ADR-20261116 / run-steer). */
public enum SteerState {
    /** Hermes 가 받아 두었다. 모델이 읽었다는 뜻은 아니다. 화면 글은 「전달됨」 이다. */
    DELIVERED,
    /** 모델에 들어가지 못하고 돌아와 다음 turn 으로 보냈거나 보낸다. 화면 글은 「다음 차례로 넘어감」 이다. */
    CARRIED_OVER,
    /** 중지나 응답 없음으로 들어갔는지 알 수 없다. 같은 글을 멈춘 대기 줄에 둔다. 화면 글은 「전달됐는지 확인 못 함」 이다. */
    UNCONFIRMED,
    /**
     * 중지로 끝났는데 Hermes 가 넣지 못한 글로 돌려줬다({@code pending_steer}). 모델이 보지 않았다. 같은 글을 멈춘 대기 줄에 두고 사람이 고른다.
     * 화면 글은 「전달 안 됨」 이다.
     */
    NOT_DELIVERED
}
