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

    /** 기억 읽기 확인이 답에서 찾는 낱말이다. */
    static final String MEMORY_FACT = "청록";

    /** 원본 사진 조회 확인의 합성 사진에 심은 글자다. 문항 글은 이 글자를 말하지 않는다. */
    static final String PHOTO_MARKER = "QX-7319";

    /** 원본 사진 조회 확인이 올리는 합성 사진이다. 표지판에 {@link #PHOTO_MARKER} 가 적혀 있다. */
    static final String PHOTO_RESOURCE = "quality/deploy-check-photo.png";

    static final String SEARCH_PROMPT = "오늘 서울 날씨를 웹에서 찾아 한두 줄로 알려 주세요. 답 끝에 「출처」 절을 두고 찾은 링크를 적어 주세요.";

    /** 위임 확인 문항이다. 위임 대상 에이전트의 code 를 넣는다. 다른 사용자의 공개 에이전트로 가지 않게 code 로 지정한다. */
    static final String DELEGATION_PROMPT = "agent_delegate 도구로 에이전트 %s 에게 「1 더하기 1 의 답을 숫자로만 쓰기」 를 맡겨 주세요."
        + " 결과는 기다리지 말고 맡겼다고만 답해 주세요.";

    static final String FILE_PUT_PROMPT = "「평가 확인」 이라고 한 줄 적은 quality-check.txt 파일을 만들고, file_space_put 도구로 내 파일 공간에 넣어 주세요.";

    static final String IMAGE_PROMPT = "흰 바탕에 파란 동그라미 하나만 있는 단순한 그림을 만들어 주세요.";

    static final String MEMORY_PROMPT = "내 기억에 적힌 것을 보고, 평가 사용자가 좋아하는 색이 무엇인지 알려 주세요.";

    static final String TASK_TITLE = "배포 뒤 확인";

    static final String TASK_INSTRUCTION = "「확인」 이라고만 답해 주세요.";

    static final String PHOTO_UPLOAD_PROMPT = "사진을 올려 둘게요. 다음 메시지에서 물어볼게요. 지금은 「받았어요」 라고만 답해 주세요.";

    static final String PHOTO_ASK_PROMPT = "앞 메시지에 올린 사진의 원본을 attachment_inspect 도구로 열어, 표지판에 적힌 글자를 그대로 알려 주세요.";
}
