package com.bifos.assistant.quality.application;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 배포 뒤 확인의 고정 값이다. 미리 넣는 기억, 사진에 심은 글자, 위임 대상 이름을 판정과 준비가 함께 쓴다.
 *
 * <p>값은 날마다 바뀌지 않는 지어낸 사실이다. 문항 글은 실행기가 더한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class DeployCheckItems {

    /** 평가 전용 사용자의 주소다. {@code .invalid} 는 메일이 가지 않는 예약 도메인이다. */
    static final String EMAIL = "evaluation@quality.invalid";

    static final String DISPLAY_NAME = "평가 사용자";

    /** 위임 확인이 맡길 에이전트의 이름이다. 평가 사용자가 주인이고 비공개다. */
    static final String DELEGATE_AGENT_NAME = "평가 위임 대상";

    /** 기억 읽기 확인이 물을 미리 넣는 기억이다. */
    static final String MEMORY_TITLE = "평가 사용자가 좋아하는 색";

    static final String MEMORY_CONTENT = "평가 사용자가 좋아하는 색은 청록이다.";

}
