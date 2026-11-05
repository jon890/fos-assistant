package com.bifos.assistant.proactive.check.application.dto;

import java.util.List;

/**
 * 사건 실행 하나가 살펴볼 새 항목이다(ADR-20261105 / event-watch). 입력의 「새 사건」 절이 이 값으로 만들어진다.
 *
 * @param sourceId 사건을 낸 원천의 번호. 살펴보기는 그 뜻을 모르고 끝날 때 {@link EventCheckEnded} 로 돌려준다
 * @param sourceTitle 원천의 이름. 예: 플러그인 제목
 * @param noun 항목 이름. 예: 「메일」
 * @param link 원문 링크 틀. {@code {id}} 를 식별자로 바꾼다
 * @param newCount 새 항목 수. 입력에 싣지 못한 것도 센다
 * @param ids 입력에 싣는 새 항목 식별자. 알린 항목은 이 안의 것만 센다
 */
public record EventBatch(Long sourceId, String sourceTitle, String noun, String link, int newCount, List<String> ids) {

    public EventBatch {
        ids = List.copyOf(ids);
    }


    /** 그 항목의 원문 링크다. */
    public String linkOf(String id) {
        return link.replace("{id}", id);
    }
}
