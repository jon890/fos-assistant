package com.bifos.assistant.quality.application.dto;

import java.util.List;

/**
 * 평가 목록 한 쪽이다. 최근 순이다.
 *
 * @param nextCursor 다음 쪽을 읽을 값. 마지막 쪽이면 null
 */
public record EvalPage(List<EvalView> items, String nextCursor) {

    public EvalPage {
        items = List.copyOf(items);
    }
}
