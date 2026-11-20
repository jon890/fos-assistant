package com.bifos.assistant.chat.artifact.application.dto;

import java.util.List;

/**
 * 실행 입력의 「이 대화에서 만든 그림」 줄에 적는 그림 하나다(스펙 generated-image REQ17, REQ25).
 *
 * @param title 그림 제목. 모델이 {@code generated:} 로 가리키는 글자다
 * @param sourceLinks 그림을 만들 때 참고한 링크 주소. 모델이 이어지는 장면에서 {@code link:} 로 다시 넣는다. 쓴 순서이고 없으면 빈 목록이다
 */
public record GeneratedImageMention(String title, List<String> sourceLinks) {

    /** 모델이 {@code link:} 뒤에 적을 수 있는 주소의 길이 상한이다. 받기 경로의 가리킴 해석과 그림 제목 줄이 같은 값을 쓴다. */
    public static final int MAX_LINK_CHARS = 2048;

    public GeneratedImageMention {
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
    }
}
