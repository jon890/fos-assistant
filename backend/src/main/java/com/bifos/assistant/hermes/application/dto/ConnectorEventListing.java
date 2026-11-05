package com.bifos.assistant.hermes.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * 대시보드 plugin 의 사건 감지 목록 조회 결과다(ADR-20261105 / event-watch). 성공 결과와 공통 어휘 가운데 하나만 있다.
 *
 * <p>항목마다 식별자, 받은 시각, 라벨만 담는다. 제목과 본문은 이 경로로 오지 않는다.
 *
 * @param items 받은 항목. 실패하면 빈 목록
 * @param truncated 쪽 상한에 닿아 더 오래된 항목을 받지 못했다
 * @param error 실패한 까닭. 성공하면 null
 */
public record ConnectorEventListing(List<Item> items, boolean truncated, ConnectorCallError error) {

    public ConnectorEventListing {
        items = items == null ? List.of() : List.copyOf(items);
    }


    /**
     * 항목 하나다.
     *
     * @param receivedAt 플러그인이 읽은 받은 시각. 읽지 못했으면 null 이다. 보낸 사람이 쓴 값일 수 있어 확인 지점으로 쓰지 않는다
     */
    public record Item(String id, Instant receivedAt, List<String> labels) {

        public Item {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }


    public static ConnectorEventListing success(List<Item> items, boolean truncated) {
        return new ConnectorEventListing(items, truncated, null);
    }


    public static ConnectorEventListing failure(ConnectorCallError error) {
        return new ConnectorEventListing(List.of(), false, error);
    }


    public boolean ok() {
        return error == null;
    }
}
