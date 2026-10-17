package com.bifos.assistant.skill.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 지우지 않은 대화의 번호를 공개 식별자로 바꾸는 조회다. 대화를 가진 쪽이 구현한다. */
public interface ActiveConversationPublicIds {

    /** 대화 번호별 공개 식별자를 한 번에 읽는다. 지운 대화는 뺀다. 번호가 비면 빈 표를 돌려준다. */
    Map<Long, UUID> activePublicIdsOf(Collection<Long> conversationIds);
}
