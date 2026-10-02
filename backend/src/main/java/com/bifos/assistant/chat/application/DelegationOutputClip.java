package com.bifos.assistant.chat.application;

/**
 * 위임 실행의 답을 실행 줄에 적을 길이로 맞춘다. 상한을 가진 {@code orchestration} 이 구현한다.
 */
public interface DelegationOutputClip {

    /**
     * 위임 답을 상한까지 자르고 잘렸다는 한 줄을 붙인다. 답이 null 이면 빈 글이다.
     *
     * <p>상한 자리에서 대리 쌍이 갈리면 그 앞에서 자른다. 반쪽 글자가 남으면 저장과 JSON 쓰기에서 깨진다.
     */
    String clip(String output);

    /** 멈춘 위임 실행이 그때까지 받은 답이다. 받은 답이 없으면 null 이라 실행 줄의 답을 비워 둔다. */
    String partial(String output);
}
