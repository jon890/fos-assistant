package com.bifos.assistant.people.application.dto;

import java.util.List;

/**
 * 사용자 정리 미리보기다. 표마다 정리 때 하는 일과 줄 수를 담는다.
 *
 * @param personId 허용 목록의 번호
 * @param items 사용자가 없으면 비어 있다
 */
public record PurgePreview(Long personId, List<Item> items) {

    /** 정리 때 하는 일이다. DELETE 는 지우고 TRANSFER 는 그룹의 관리자에게 넘긴다. */
    public enum Action {
        DELETE,
        TRANSFER
    }

    public record Item(String table, Action action, long rows) {
    }
}
