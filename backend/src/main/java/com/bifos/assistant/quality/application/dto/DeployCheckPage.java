package com.bifos.assistant.quality.application.dto;

import java.util.List;

/**
 * 배포 뒤 확인 목록 한 쪽이다. 최근 순이다.
 *
 * @param nextCursor 다음 쪽을 읽을 값. 마지막 쪽이면 null
 */
public record DeployCheckPage(List<DeployCheckView> items, String nextCursor) {

    public DeployCheckPage {
        items = List.copyOf(items);
    }
}
