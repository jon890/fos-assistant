package com.bifos.assistant.chat.artifact.application.dto;

import java.util.List;

/**
 * 답에 묶인 그림 하나를 화면에 보일 때 더하는 값이다.
 *
 * @param prompt 그림 지시문. 풀지 못하면 null
 * @param vaultItemId 그 그림의 살아 있는 보관본 번호. 없으면 null
 * @param sourceLinks 그림을 만들 때 참고한 링크 주소. 쓴 순서다. 없으면 빈 목록이다
 */
public record GeneratedImageDetail(String prompt, Long vaultItemId, List<String> sourceLinks) {

    public GeneratedImageDetail {
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
    }
}
