package com.bifos.assistant.chat.artifact.application.dto;

import java.util.List;

/**
 * 실행 입력의 「이 대화에서 만든 그림」 줄에 적는 그림 하나다(스펙 generated-image REQ17, REQ25).
 *
 * @param title 그림 제목. 모델이 {@code generated:} 로 가리키는 글자다
 * @param sourceLinks 그림을 만들 때 참고한 링크 주소. 모델이 이어지는 장면에서 {@code link:} 로 다시 넣는다. 쓴 순서이고 없으면 빈 목록이다
 */
public record GeneratedImageMention(String title, List<String> sourceLinks) {

    public GeneratedImageMention {
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
    }
}
