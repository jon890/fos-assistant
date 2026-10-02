package com.bifos.assistant.chat.application;

/** 추천 질문을 읽었을 때의 상태다. */
public enum StarterStatus {
    /** 만들어 둔 추천이 있다. 다시 만드는 중이어도 이전 추천을 준다. */
    READY,
    /** 추천을 만드는 중이다. 화면이 잠시 뒤 다시 읽는다. */
    GENERATING,
    /** 추천이 없고 만들지도 않는다. 꺼져 있거나 방금 만들기가 실패했다. */
    NONE
}
